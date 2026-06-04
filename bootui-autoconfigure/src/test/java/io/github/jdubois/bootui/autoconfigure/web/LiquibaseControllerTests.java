package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.sql.SQLException;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
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

    @Test
    void changeSetsReturnsEmptyReportWhenNoBeanFactory() throws Exception {
        MockMvc mvc = standaloneSetup(new LiquibaseController(emptyProvider())).build();

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

        MockMvc mvc =
                standaloneSetup(new LiquibaseController(providerOf(factory))).build();

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

        MockMvc mvc =
                standaloneSetup(new LiquibaseController(providerOf(factory))).build();

        mvc.perform(get("/bootui/api/liquibase/changesets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.databases[0].name").value("liquibase"))
                .andExpect(jsonPath("$.databases[0].total").value(0))
                .andExpect(jsonPath("$.databases[0].changeSets").isEmpty());
    }
}
