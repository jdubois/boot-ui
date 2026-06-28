package io.github.jdubois.bootui.autoconfigure.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.FlywayActionRequest;
import io.github.jdubois.bootui.core.dto.FlywayDatabaseDto;
import io.github.jdubois.bootui.core.dto.FlywayReport;
import io.github.jdubois.bootui.engine.flyway.FlywayActionResponse;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.CleanResult;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.core.ApplicationModuleIdentifiers;
import org.springframework.modulith.runtime.flyway.SpringModulithFlywayMigrationStrategy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Behavioural tests for {@link SpringFlywayProvider}, exercised through the engine {@link FlywayService} so
 * the full Spring seam (bean discovery, {@code MigrationInfo} mapping, the Spring-Modulith module-aware block,
 * and the {@code migrate}/{@code clean} primitives) is covered exactly as the former {@code FlywayController}
 * was. This is the byte-identical extraction target; the HTTP binding is covered by {@code FlywayControllerTests}.
 */
@Testcontainers(disabledWithoutDocker = true)
class SpringFlywayProviderTests {

    private static final String MODULITH_ACTIONS_DISABLED =
            "Spring Modulith module-aware Flyway migrations use module-specific history tables and are read-only in BootUI.";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

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

    private static FlywayService service(ListableBeanFactory factory) {
        return new FlywayService(new SpringFlywayProvider(providerOf(factory)));
    }

    private static ListableBeanFactory factoryWithFlyway(String beanName, Flyway flyway) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(Flyway.class)).thenReturn(new String[] {beanName});
        when(factory.getBean(eq(beanName), eq(Flyway.class))).thenReturn(flyway);
        return factory;
    }

    private static ListableBeanFactory factoryWithModuleAwareFlyway(
            String beanName, Flyway flyway, String... identifiers) {
        ListableBeanFactory factory = factoryWithFlyway(beanName, flyway);
        when(factory.getBeanNamesForType(SpringModulithFlywayMigrationStrategy.class, false, false))
                .thenReturn(new String[] {"springModulithFlywayMigrationStrategy"});
        when(factory.getBeanNamesForType(ApplicationModuleIdentifiers.class, false, false))
                .thenReturn(new String[] {"applicationModuleIdentifiers"});
        when(factory.getBean("applicationModuleIdentifiers", ApplicationModuleIdentifiers.class))
                .thenReturn(ApplicationModuleIdentifiers.of(identifiers));
        return factory;
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

    private static Flyway flywayFor(Path location) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + location.toAbsolutePath())
                .load();
    }

    private static void writeMigration(Path location, String identifier, String fileName, String sql) throws Exception {
        Path directory = location.resolve(identifier.replace('.', '/'));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), sql);
    }

    private static void migrateModule(Flyway flyway, String identifier) {
        Configuration configuration = flyway.getConfiguration();
        List<String> locations = Stream.of(configuration.getLocations())
                .map(Location::toString)
                .map(location -> location.endsWith("*") ? location : location + "/" + identifier.replace('.', '/'))
                .toList();
        Flyway.configure()
                .configuration(configuration)
                .locations(locations.toArray(String[]::new))
                .table(
                        "__root".equals(identifier)
                                ? configuration.getTable()
                                : configuration.getTable() + "_" + identifier)
                .baselineVersion("0")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    @Test
    void reportIsEmptyButPresentWhenNoBeanFactory() {
        FlywayService service = new FlywayService(new SpringFlywayProvider(emptyProvider()));

        FlywayReport report = service.report();

        assertThat(report.flywayPresent()).isTrue();
        assertThat(report.total()).isZero();
        assertThat(report.databases()).isEmpty();
    }

    @Test
    void reportIsEmptyButPresentWhenNoFlywayBeans() {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(Flyway.class)).thenReturn(new String[0]);

        FlywayReport report = service(factory).report();

        assertThat(report.flywayPresent()).isTrue();
        assertThat(report.total()).isZero();
        assertThat(report.databases()).isEmpty();
    }

    @Test
    void reportSummarizesAppliedAndPendingMigrations() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo[] all = new MigrationInfo[] {
            migration("1", "create catalog", MigrationState.SUCCESS),
            migration("2", "seed catalog", MigrationState.PENDING)
        };
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(all);

        FlywayReport report = service(factoryWithFlyway("flyway", flyway)).report();

        assertThat(report.total()).isEqualTo(2);
        FlywayDatabaseDto database = report.databases().get(0);
        assertThat(database.name()).isEqualTo("flyway");
        assertThat(database.currentVersion()).isEqualTo("1");
        assertThat(database.applied()).isEqualTo(1);
        assertThat(database.pending()).isEqualTo(1);
        assertThat(database.migrateEnabled()).isTrue();
        assertThat(database.cleanEnabled()).isTrue();
        assertThat(database.migrations().get(0).version()).isEqualTo("1");
        assertThat(database.migrations().get(0).state()).isEqualTo("Success");
        assertThat(database.migrations().get(1).state()).isEqualTo("Pending");
    }

    @Test
    void reportSummarizesSpringModulithModuleAwareHistories(@TempDir Path migrations) throws Exception {
        writeMigration(migrations, "__root", "V1__create_root.sql", "CREATE TABLE root_item (id INT);\n");
        writeMigration(migrations, "orders", "V1__create_orders.sql", "CREATE TABLE orders_item (id INT);\n");
        writeMigration(migrations, "inventory", "V1__create_inventory.sql", "CREATE TABLE inventory_item (id INT);\n");
        Flyway flyway = flywayFor(migrations);
        migrateModule(flyway, "__root");
        migrateModule(flyway, "orders");
        migrateModule(flyway, "inventory");

        FlywayReport report = service(factoryWithModuleAwareFlyway("flyway", flyway, "orders", "inventory"))
                .report();

        assertThat(report.total()).isEqualTo(5);
        assertThat(report.databases().get(0).name()).isEqualTo("flyway:__root");
        assertThat(report.databases().get(0).applied()).isEqualTo(1);
        assertThat(report.databases().get(0).migrations().get(0).description()).isEqualTo("create root");
        assertThat(report.databases().get(0).migrateEnabled()).isFalse();
        assertThat(report.databases().get(0).migrateDisabledReason()).isEqualTo(MODULITH_ACTIONS_DISABLED);
        assertThat(report.databases().get(0).cleanEnabled()).isFalse();
        assertThat(report.databases().get(0).cleanDisabledReason()).isEqualTo(MODULITH_ACTIONS_DISABLED);
        assertThat(report.databases().get(1).name()).isEqualTo("flyway:inventory");
        assertThat(report.databases().get(1).applied()).isEqualTo(2);
        assertThat(report.databases().get(2).name()).isEqualTo("flyway:orders");
        assertThat(report.databases().get(2).applied()).isEqualTo(2);
    }

    @Test
    void migrateRequiresConfirmation() {
        Flyway flyway = mock(Flyway.class);

        FlywayActionResponse response =
                service(factoryWithFlyway("flyway", flyway)).migrate(new FlywayActionRequest("flyway", null));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("blocked");
        assertThat(response.body().message())
                .isEqualTo("Action requires confirm=true because it mutates the application database.");
        verify(flyway, never()).migrate();
    }

    @Test
    void migrateRunsTheSelectedFlywayBean() {
        Flyway flyway = mock(Flyway.class);
        MigrateResult result = new MigrateResult();
        result.success = true;
        result.migrationsExecuted = 2;
        result.warnings = List.of("Review generated callbacks");
        when(flyway.migrate()).thenReturn(result);

        FlywayActionResponse response = service(factoryWithFlyway("ordersFlyway", flyway))
                .migrate(new FlywayActionRequest("ordersFlyway", true));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("success");
        assertThat(response.body().beanName()).isEqualTo("ordersFlyway");
        assertThat(response.body().migrationsExecuted()).isEqualTo(2);
        assertThat(response.body().warnings()).containsExactly("Review generated callbacks");
        verify(flyway).migrate();
    }

    @Test
    void migrateIsBlockedWhenSpringModulithModuleAwareStrategyIsActive() {
        Flyway flyway = mock(Flyway.class);

        FlywayActionResponse response = service(factoryWithModuleAwareFlyway("flyway", flyway, "orders"))
                .migrate(new FlywayActionRequest("flyway:orders", true));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().status()).isEqualTo("blocked");
        assertThat(response.body().beanName()).isEqualTo("flyway:orders");
        assertThat(response.body().message()).isEqualTo(MODULITH_ACTIONS_DISABLED);
        verify(flyway, never()).migrate();
    }

    @Test
    void cleanRequiresFlywayToAllowClean() {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(configuration.isCleanDisabled()).thenReturn(true);
        when(flyway.getConfiguration()).thenReturn(configuration);

        FlywayActionResponse response =
                service(factoryWithFlyway("flyway", flyway)).clean(new FlywayActionRequest("flyway", true));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().status()).isEqualTo("blocked");
        assertThat(response.body().message())
                .isEqualTo(
                        "Flyway clean is disabled by Flyway configuration. Set spring.flyway.clean-disabled=false to allow it.");
        verify(flyway, never()).clean();
    }

    @Test
    void cleanRunsWhenFlywayAllowsItAndRequestIsConfirmed() {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(configuration.isCleanDisabled()).thenReturn(false);
        when(flyway.getConfiguration()).thenReturn(configuration);
        CleanResult result = new CleanResult("11.14.1", "PostgreSQL");
        result.schemasCleaned = new ArrayList<>(List.of("public"));
        result.schemasDropped = new ArrayList<>(List.of("archive"));
        result.warnings = List.of();
        when(flyway.clean()).thenReturn(result);

        FlywayActionResponse response =
                service(factoryWithFlyway("flyway", flyway)).clean(new FlywayActionRequest("flyway", true));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("success");
        assertThat(response.body().schemasCleaned()).containsExactly("public");
        assertThat(response.body().schemasDropped()).containsExactly("archive");
        verify(flyway).clean();
    }
}
