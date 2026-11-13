package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.spi.BeanManager;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class BootUiQuarkusRuntimeDiagnosticsTest {

    @Test
    void absentOrCompatibleAgentsAndJdbcOnlyApplicationsDoNotWarn() {
        assertThat(BootUiQuarkusRuntimeDiagnostics.warnings(null, false)).isEmpty();
        assertThat(BootUiQuarkusRuntimeDiagnostics.reactiveSqlDeclared(
                        config(Map.of(
                                "quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost/app",
                                "quarkus.datasource.db-kind", "postgresql")),
                        Set.of("javax.sql.DataSource")))
                .isFalse();
    }

    @Test
    void anIncompatibleAttachedAgentWarnsWithoutHidingOtherDiagnostics() {
        assertThat(BootUiQuarkusRuntimeDiagnostics.warnings("bridge protocol mismatch", true))
                .anySatisfy(message ->
                        assertThat(message).contains("BOOTUI-COMPAT-051", "bridge protocol mismatch", "remains unused"))
                .anySatisfy(message -> assertThat(message).contains("BOOTUI-COMPAT-041", "JDBC only"));
    }

    @Test
    void aReactiveUrlWarnsEvenWhenTheBannerIsDisabledAndJdbcIsAlsoConfigured() {
        var config = config(Map.of(
                "bootui.show-banner", "false",
                "quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost/app",
                "quarkus.datasource.reactive.url", "postgresql://localhost/app"));

        assertThat(BootUiQuarkusRuntimeDiagnostics.reactiveSqlDeclared(config, Set.of("javax.sql.DataSource")))
                .isTrue();
    }

    @Test
    void namedReactiveUrlsAreRecognizedWithoutLoggingTheirValues() {
        var config = config(Map.of(
                "quarkus.datasource.\"orders\".reactive.url", "postgresql://user:secret@localhost/app",
                "quarkus.datasource.\"orders\".jdbc.url", "jdbc:postgresql://localhost/app"));

        boolean declared = BootUiQuarkusRuntimeDiagnostics.reactiveSqlDeclared(config, Set.of());
        assertThat(declared).isTrue();
        assertThat(BootUiQuarkusRuntimeDiagnostics.warnings(null, declared))
                .allSatisfy(message -> assertThat(message).doesNotContain("secret", "localhost", "orders"));
    }

    @Test
    void explicitlyDisabledOrEmptyReactiveDatasourceDeclarationsDoNotWarn() {
        for (String disabledKey : Set.of(
                "quarkus.datasource.active",
                "quarkus.datasource.reactive.active",
                "quarkus.datasource.reactive.enabled")) {
            assertThat(BootUiQuarkusRuntimeDiagnostics.reactiveSqlDeclared(
                            config(Map.of(
                                    "quarkus.datasource.reactive.url",
                                    "postgresql://localhost/app",
                                    disabledKey,
                                    "false")),
                            Set.of()))
                    .as(disabledKey)
                    .isFalse();
        }
        assertThat(BootUiQuarkusRuntimeDiagnostics.reactiveSqlDeclared(
                        config(Map.of("quarkus.datasource.reactive.url", "")), Set.of()))
                .isFalse();
    }

    @Test
    void actualCdiClientDeclarationsWarnWithoutInitializingClients() {
        var config = config(Map.of());

        assertThat(BootUiQuarkusRuntimeDiagnostics.reactiveSqlDeclared(
                        config, Set.of("io.vertx.mutiny.sqlclient.Pool", "javax.sql.DataSource")))
                .isTrue();
        assertThat(BootUiQuarkusRuntimeDiagnostics.reactiveSqlDeclared(
                        config, Set.of("org.hibernate.reactive.mutiny.Mutiny$SessionFactory")))
                .isTrue();
        assertThat(BootUiQuarkusRuntimeDiagnostics.reactiveSqlDeclared(config, Set.of("java.lang.Object")))
                .isFalse();
    }

    @Test
    void startupLogsCoverageWithTheBannerOffWithoutResolvingAnyCdiBean() {
        BeanManager manager = (BeanManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {BeanManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBeans")) {
                        return Set.of();
                    }
                    throw new AssertionError("Startup must not resolve client instances: " + method);
                });
        var diagnostics = new BootUiQuarkusRuntimeDiagnostics(
                config(Map.of(
                        "bootui.show-banner", "false",
                        "quarkus.datasource.reactive.url", "postgresql://localhost/app")),
                manager);
        var records = new ArrayList<LogRecord>();
        var logger = Logger.getLogger(BootUiQuarkusRuntimeDiagnostics.class.getName());
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            diagnostics.onStart(new StartupEvent());

            assertThat(records)
                    .anySatisfy(record -> assertThat(record.getMessage()).contains("BOOTUI-COMPAT-041"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static Config config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 500))
                .build();
    }
}
