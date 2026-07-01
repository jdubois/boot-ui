package io.github.jdubois.bootui.sample.config;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * Wires the database-migration demo so the BootUI Flyway and Liquibase panels have pending work to
 * show: only the base schema is applied on startup, leaving later migrations and change sets pending.
 */
@Configuration(proxyBeanMethods = false)
class SampleMigrationConfiguration {

    private static final String FLYWAY_STARTUP_TARGET = "2";
    private static final String LIQUIBASE_BASE_CHANGELOG = "classpath:db/changelog/db.changelog-base.xml";
    private static final String LIQUIBASE_MASTER_CHANGELOG = "classpath:db/changelog/db.changelog-master.xml";

    @Bean
    @ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
    FlywayMigrationStrategy sampleFlywayStartupStrategy() {
        return flyway -> {
            // BootUI should demonstrate pending migrations, so the sample applies
            // its base schema in a runner below.
        };
    }

    @Bean
    ApplicationRunner sampleMigrationDemoInitializer(
            ObjectProvider<Flyway> flywayProvider,
            ObjectProvider<SpringLiquibase> liquibaseProvider,
            DataSource dataSource,
            ResourceLoader resourceLoader) {
        return args -> {
            // The sample migrations are disabled by default in the Docker images for a faster
            // startup (and can be toggled with SPRING_FLYWAY_ENABLED / SPRING_LIQUIBASE_ENABLED),
            // so this demo wiring must tolerate either tool being absent rather than fail to start.
            // Gating on bean presence (rather than reading the property at runtime) keeps the
            // behaviour consistent for the native image, where the toggle is baked in at build time.
            Flyway flyway = flywayProvider.getIfAvailable();
            if (flyway != null) {
                Flyway.configure()
                        .configuration(flyway.getConfiguration())
                        .target(FLYWAY_STARTUP_TARGET)
                        .load()
                        .migrate();
            }

            if (liquibaseProvider.getIfAvailable() != null) {
                SpringLiquibase baseLiquibase = new SpringLiquibase();
                baseLiquibase.setDataSource(dataSource);
                baseLiquibase.setResourceLoader(resourceLoader);
                baseLiquibase.setChangeLog(LIQUIBASE_BASE_CHANGELOG);
                baseLiquibase.setShouldRun(true);
                baseLiquibase.afterPropertiesSet();
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.liquibase", name = "enabled", matchIfMissing = true)
    SpringLiquibase liquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(LIQUIBASE_MASTER_CHANGELOG);
        liquibase.setShouldRun(false);
        return liquibase;
    }
}
