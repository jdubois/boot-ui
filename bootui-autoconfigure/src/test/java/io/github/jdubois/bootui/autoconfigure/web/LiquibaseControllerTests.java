package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link LiquibaseController}.
 *
 * <p>Covers the empty-context cases (no bean factory, no Liquibase beans) and the
 * fail-closed behaviour when a bean's change-log history table cannot be read.</p>
 */
class LiquibaseControllerTests {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static MockMvc mvc(ListableBeanFactory factory, BootUiProperties properties) {
        return standaloneSetup(new LiquibaseController(providerOf(factory), properties))
                .build();
    }

    private static MockMvc mvc(
            ListableBeanFactory factory,
            BootUiProperties properties,
            LiquibaseController.LiquibaseActionExecutor actionExecutor) {
        return standaloneSetup(new LiquibaseController(providerOf(factory), properties, actionExecutor))
                .build();
    }

    private static ListableBeanFactory factoryWithLiquibase(String beanName, SpringLiquibase liquibase) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(SpringLiquibase.class)).thenReturn(new String[] {beanName});
        when(factory.getBean(eq(beanName), eq(SpringLiquibase.class))).thenReturn(liquibase);
        return factory;
    }

    @Test
    void changeSetsReturnsEmptyReportWhenNoBeanFactory() throws Exception {
        MockMvc mvc = standaloneSetup(new LiquibaseController(emptyProvider(), new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/liquibase/changesets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liquibasePresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.databases").isEmpty());
    }

    @Test
    void changeSetsReturnsEmptyReportWhenNoLiquibaseBeans() throws Exception {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(SpringLiquibase.class)).thenReturn(new String[0]);

        MockMvc mvc = mvc(factory, new BootUiProperties());

        mvc.perform(get("/bootui/api/liquibase/changesets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.databases").isEmpty());
    }

    @Test
    void changeSetsFailsClosedWhenHistoryTableIsUnreadable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        SpringLiquibase liquibase = mock(SpringLiquibase.class);
        when(liquibase.getDataSource()).thenReturn(dataSource);

        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(SpringLiquibase.class)).thenReturn(new String[] {"liquibase"});
        when(factory.getBean(eq("liquibase"), eq(SpringLiquibase.class))).thenReturn(liquibase);

        MockMvc mvc = mvc(factory, new BootUiProperties());

        mvc.perform(get("/bootui/api/liquibase/changesets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.databases[0].name").value("liquibase"))
                .andExpect(jsonPath("$.databases[0].total").value(0))
                .andExpect(jsonPath("$.databases[0].updateEnabled").value(false))
                .andExpect(jsonPath("$.databases[0].dropAllEnabled").value(false))
                .andExpect(jsonPath("$.databases[0].generateChangeLogEnabled").value(false))
                .andExpect(jsonPath("$.databases[0].changeSets").isEmpty());
    }

    @Test
    void updateIsBlockedUntilExplicitlyEnabled() throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        LiquibaseController.LiquibaseActionExecutor executor = mock(LiquibaseController.LiquibaseActionExecutor.class);
        MockMvc mvc = mvc(factoryWithLiquibase("liquibase", liquibase), new BootUiProperties(), executor);

        mvc.perform(post("/bootui/api/liquibase/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"liquibase\",\"confirm\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.beanName").value("liquibase"))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Liquibase update is disabled by default. Set bootui.liquibase.update-enabled=true in a trusted local profile."));

        verify(executor, never()).update(eq("liquibase"), eq(liquibase));
    }

    @Test
    void updateRequiresConfirmationAfterItIsEnabled() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getLiquibase().setUpdateEnabled(true);
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(mock(DataSource.class));
        liquibase.setChangeLog("classpath:/db/changelog.xml");
        LiquibaseController.LiquibaseActionExecutor executor = mock(LiquibaseController.LiquibaseActionExecutor.class);
        MockMvc mvc = mvc(factoryWithLiquibase("liquibase", liquibase), properties, executor);

        mvc.perform(post("/bootui/api/liquibase/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"liquibase\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.message")
                        .value("Action requires confirm=true because it mutates the application database."));

        verify(executor, never()).update(eq("liquibase"), eq(liquibase));
    }

    @Test
    void updateRunsTheSelectedLiquibaseBean() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.getLiquibase().setUpdateEnabled(true);
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(mock(DataSource.class));
        liquibase.setChangeLog("classpath:/db/changelog.xml");
        LiquibaseController.LiquibaseActionExecutor executor = mock(LiquibaseController.LiquibaseActionExecutor.class);
        when(executor.update(eq("inventoryLiquibase"), eq(liquibase)))
                .thenReturn(new LiquibaseActionResult(
                        "success", "Liquibase applied 2 change set(s).", "inventoryLiquibase", 3, 1, 2, List.of()));
        MockMvc mvc = mvc(factoryWithLiquibase("inventoryLiquibase", liquibase), properties, executor);

        mvc.perform(post("/bootui/api/liquibase/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"inventoryLiquibase\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.beanName").value("inventoryLiquibase"))
                .andExpect(jsonPath("$.changeSetsApplied").value(2));

        verify(executor).update(eq("inventoryLiquibase"), eq(liquibase));
    }
}
