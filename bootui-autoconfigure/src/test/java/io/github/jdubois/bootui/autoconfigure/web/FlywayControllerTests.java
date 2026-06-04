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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.CleanResult;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link FlywayController}.
 *
 * <p>Covers the empty-context cases (no bean factory, no Flyway beans) and a
 * discovered Flyway bean whose {@code info().all()} exposes one applied and one
 * pending migration.</p>
 */
class FlywayControllerTests {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static MockMvc mvc(ListableBeanFactory factory) {
        return standaloneSetup(new FlywayController(providerOf(factory))).build();
    }

    private static ListableBeanFactory factoryWithFlyway(String beanName, Flyway flyway) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(Flyway.class)).thenReturn(new String[] {beanName});
        when(factory.getBean(eq(beanName), eq(Flyway.class))).thenReturn(flyway);
        return factory;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static MigrationInfo migration(String version, String description, MigrationState state) {
        MigrationInfo info = mock(MigrationInfo.class);
        when(info.getType()).thenReturn(CoreMigrationType.SQL);
        when(info.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(info.getDescription()).thenReturn(description);
        when(info.getScript()).thenReturn("V" + version + "__" + description + ".sql");
        when(info.getState()).thenReturn(state);
        when(info.getInstalledRank()).thenReturn(1);
        when(info.getChecksum()).thenReturn(123);
        if (state.isApplied()) {
            when(info.getInstalledBy()).thenReturn("sa");
            when(info.getInstalledOn()).thenReturn(new Date(0L));
            when(info.getExecutionTime()).thenReturn(7);
        }
        return info;
    }

    @Test
    void migrationsReturnsEmptyReportWhenNoBeanFactory() throws Exception {
        MockMvc mvc = standaloneSetup(new FlywayController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/flyway/migrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flywayPresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.databases").isEmpty());
    }

    @Test
    void migrationsReturnsEmptyReportWhenNoFlywayBeans() throws Exception {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(Flyway.class)).thenReturn(new String[0]);

        MockMvc mvc = mvc(factory);

        mvc.perform(get("/bootui/api/flyway/migrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.databases").isEmpty());
    }

    @Test
    void migrationsSummarizesAppliedAndPendingMigrations() throws Exception {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo[] all = new MigrationInfo[] {
            migration("1", "create catalog", MigrationState.SUCCESS),
            migration("2", "seed catalog", MigrationState.PENDING)
        };
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(all);

        MockMvc mvc = mvc(factoryWithFlyway("flyway", flyway));

        mvc.perform(get("/bootui/api/flyway/migrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.databases[0].name").value("flyway"))
                .andExpect(jsonPath("$.databases[0].currentVersion").value("1"))
                .andExpect(jsonPath("$.databases[0].applied").value(1))
                .andExpect(jsonPath("$.databases[0].pending").value(1))
                .andExpect(jsonPath("$.databases[0].migrateEnabled").value(true))
                .andExpect(jsonPath("$.databases[0].cleanEnabled").value(true))
                .andExpect(jsonPath("$.databases[0].migrations[0].version").value("1"))
                .andExpect(jsonPath("$.databases[0].migrations[0].state").value("Success"))
                .andExpect(jsonPath("$.databases[0].migrations[1].state").value("Pending"));
    }

    @Test
    void migrateRequiresConfirmation() throws Exception {
        Flyway flyway = mock(Flyway.class);
        MockMvc mvc = mvc(factoryWithFlyway("flyway", flyway));

        mvc.perform(post("/bootui/api/flyway/migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"flyway\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.message")
                        .value("Action requires confirm=true because it mutates the application database."));

        verify(flyway, never()).migrate();
    }

    @Test
    void migrateRunsTheSelectedFlywayBean() throws Exception {
        Flyway flyway = mock(Flyway.class);
        MigrateResult result = new MigrateResult();
        result.success = true;
        result.migrationsExecuted = 2;
        result.warnings = List.of("Review generated callbacks");
        when(flyway.migrate()).thenReturn(result);
        MockMvc mvc = mvc(factoryWithFlyway("ordersFlyway", flyway));

        mvc.perform(post("/bootui/api/flyway/migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"ordersFlyway\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.beanName").value("ordersFlyway"))
                .andExpect(jsonPath("$.migrationsExecuted").value(2))
                .andExpect(jsonPath("$.warnings[0]").value("Review generated callbacks"));

        verify(flyway).migrate();
    }

    @Test
    void cleanRequiresFlywayToAllowClean() throws Exception {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(configuration.isCleanDisabled()).thenReturn(true);
        when(flyway.getConfiguration()).thenReturn(configuration);
        MockMvc mvc = mvc(factoryWithFlyway("flyway", flyway));

        mvc.perform(post("/bootui/api/flyway/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"flyway\",\"confirm\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Flyway clean is disabled by Flyway configuration. Set spring.flyway.clean-disabled=false to allow it."));

        verify(flyway, never()).clean();
    }

    @Test
    void cleanRunsWhenFlywayAllowsItAndRequestIsConfirmed() throws Exception {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(configuration.isCleanDisabled()).thenReturn(false);
        when(flyway.getConfiguration()).thenReturn(configuration);
        CleanResult result = new CleanResult("11.14.1", "PostgreSQL");
        result.schemasCleaned = new ArrayList<>(List.of("public"));
        result.schemasDropped = new ArrayList<>(List.of("archive"));
        result.warnings = List.of();
        when(flyway.clean()).thenReturn(result);
        MockMvc mvc = mvc(factoryWithFlyway("flyway", flyway));

        mvc.perform(post("/bootui/api/flyway/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"flyway\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.schemasCleaned[0]").value("public"))
                .andExpect(jsonPath("$.schemasDropped[0]").value("archive"));

        verify(flyway).clean();
    }
}
