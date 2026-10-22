package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** Driver evidence read inside the connection the introspector already borrowed, over a real H2 connection. */
class SchemaIntrospectorDriverEvidenceTests {

    private static final String SECRET_URL =
            "jdbc:h2:mem:driver-evidence?user=app&password=hunter2&reWriteBatchedInserts=true";

    private static SchemaSnapshot introspect(Map<String, Callable<Object>> overrides, Map<String, String> pool) {
        return SchemaIntrospector.introspect(
                "primary",
                () -> withMetadata(
                        DriverManager.getConnection("jdbc:h2:mem:driver-evidence-" + System.nanoTime()), overrides),
                ScanBudget.of(Duration.ofSeconds(30)),
                DatabaseAdvisorLimits.DEFAULTS,
                pool);
    }

    @Test
    void readsTheDriverNameVersionAndAllowListedUrlSettingsAndDiscardsTheUrl() {
        SchemaSnapshot snapshot = introspect(
                Map.of("getURL", () -> SECRET_URL, "getDriverName", () -> "PostgreSQL JDBC Driver"), Map.of());

        assertThat(snapshot.available()).isTrue();
        DriverEvidence driver = snapshot.driver();
        assertThat(driver.driverName()).isEqualTo("PostgreSQL JDBC Driver");
        assertThat(driver.driverVersion()).isNotBlank();
        assertThat(driver.settings()).containsOnlyKeys("reWriteBatchedInserts");
        assertThat(driver.urlSettingsKnown()).isTrue();
        assertThat(driver.poolSettingsKnown()).isTrue();
        assertThat(snapshot.toString()).doesNotContain("hunter2", "password", "user=app", "driver-evidence");
    }

    @Test
    void theSnapshotRecordsExposeNoUrlOrCredentialComponent() {
        assertThat(SchemaSnapshot.class.getRecordComponents())
                .extracting(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("url") || name.contains("password") || name.contains("user"));
        assertThat(DriverEvidence.class.getRecordComponents())
                .extracting(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("url") && !name.equals("urlsettingsknown"));
    }

    @Test
    void mergesTheSettingsThePoolSuppliesAndMarksUnreadablePoolPropertiesUnknown() {
        SchemaSnapshot withPool = introspect(
                Map.of("getURL", () -> "jdbc:h2:mem:x", "getDriverName", () -> "MySQL Connector/J"),
                Map.of("useAffectedRows", "true"));
        assertThat(withPool.driver().settings().get("useAffectedRows").source()).isEqualTo(DriverSetting.Source.POOL);
        assertThat(withPool.driver().poolSettingsKnown()).isTrue();

        SchemaSnapshot withoutPool = introspect(Map.of("getDriverName", () -> "MySQL Connector/J"), null);
        assertThat(withoutPool.driver().poolSettingsKnown()).isFalse();
    }

    @Test
    void aFailingDriverVersionBecomesADiagnosticAndTheScanCompletes() {
        SchemaSnapshot snapshot = introspect(
                Map.of("getDriverVersion", () -> {
                    throw new SQLException("jdbc:postgresql://h/db?password=hunter2 failed");
                }),
                Map.of());

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.driver().driverVersion()).isNull();
        assertThat(snapshot.driver().driverName()).isNotNull();
        assertThat(snapshot.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("driver version"));
        assertThat(snapshot.diagnostics().toString()).doesNotContain("hunter2");
    }

    @Test
    void aFailingUrlBecomesAWarningAndLeavesTheUrlSettingsUnknown() {
        SchemaSnapshot snapshot = introspect(
                Map.of("getURL", () -> {
                    throw new SQLException("jdbc:mysql://h/db?password=hunter2");
                }),
                Map.of());

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.driver().urlSettingsKnown()).isFalse();
        assertThat(snapshot.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.level()).isEqualTo(SchemaDiagnostic.WARNING));
        assertThat(snapshot.diagnostics().toString()).doesNotContain("hunter2");
    }

    @Test
    void aFailingDriverNameBecomesAWarningAndStillMergesThePoolSettings() {
        SchemaSnapshot snapshot = introspect(
                Map.of("getDriverName", () -> {
                    throw new SQLException("boom");
                }),
                Map.of("useAffectedRows", "true"));

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.driver().driverName()).isNull();
        assertThat(snapshot.driver().settings()).containsKey("useAffectedRows");
        assertThat(snapshot.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("driver name"));
    }

    @Test
    void aNullUrlIsAnInformationalDiagnosticNotAFailure() {
        SchemaSnapshot snapshot = introspect(Map.of("getURL", () -> null), Map.of());

        assertThat(snapshot.driver().urlSettingsKnown()).isFalse();
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo(SchemaDiagnostic.INFO);
            assertThat(diagnostic.message()).contains("no JDBC URL");
        });
    }

    private static Connection withMetadata(Connection real, Map<String, Callable<Object>> overrides) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getMetaData")) {
                        DatabaseMetaData metaData = real.getMetaData();
                        return Proxy.newProxyInstance(
                                DatabaseMetaData.class.getClassLoader(),
                                new Class<?>[] {DatabaseMetaData.class},
                                (inner, call, callArgs) -> {
                                    Callable<Object> override = new HashMap<>(overrides).get(call.getName());
                                    if (override != null) {
                                        return override.call();
                                    }
                                    return invoke(metaData, call, callArgs);
                                });
                    }
                    return invoke(real, method, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
