package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The server-settings read runs inside the connection the introspector already borrowed, bounded like every other
 * vendor read: a failure is a diagnostic and leaves the schema, and so the schema rules, intact.
 */
class SchemaIntrospectorServerSettingsTests {

    private static SchemaSnapshot introspectAsMySql(boolean failSettings) {
        return SchemaIntrospector.introspect(
                "primary",
                () -> mysqlLooking(
                        DriverManager.getConnection("jdbc:h2:mem:server-settings-" + System.nanoTime()), failSettings),
                ScanBudget.of(Duration.ofSeconds(30)),
                DatabaseAdvisorLimits.DEFAULTS,
                null);
    }

    @Test
    void theServerSettingsAreReadIntoTheSnapshot() {
        SchemaSnapshot snapshot = introspectAsMySql(false);

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.dialect()).isEqualTo(Dialect.MYSQL);
        assertThat(snapshot.vendorFindings().findings(VendorFindingKinds.MYSQL_SERVER_SETTINGS))
                .containsExactly(
                        new ServerSetting("wait_timeout", "600", null),
                        new ServerSetting("max_connections", "151", null));
    }

    @Test
    void aFailingServerSettingsReadIsADiagnosticAndTheSchemaIsStillRead() {
        SchemaSnapshot snapshot = introspectAsMySql(true);

        assertThat(snapshot.available()).isTrue();
        VendorAugmentation<ServerSetting> settings =
                snapshot.vendorFindings().augmentation(VendorFindingKinds.MYSQL_SERVER_SETTINGS);
        assertThat(settings.failed()).isTrue();
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo(SchemaDiagnostic.WARNING);
            assertThat(diagnostic.message()).contains("MySQL/MariaDB server settings could not be read");
        });
        assertThat(snapshot.diagnostics().toString()).doesNotContain("hunter2");
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.tables()).isNotNull();
    }

    private static Connection mysqlLooking(Connection real, boolean failSettings) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getMetaData")) {
                        DatabaseMetaData metaData = real.getMetaData();
                        return Proxy.newProxyInstance(
                                DatabaseMetaData.class.getClassLoader(),
                                new Class<?>[] {DatabaseMetaData.class},
                                (inner, call, callArgs) -> switch (call.getName()) {
                                    case "getDatabaseProductName" -> "MySQL";
                                    case "getDatabaseProductVersion" -> "8.4.0";
                                    case "getDatabaseMajorVersion" -> 8;
                                    case "getDatabaseMinorVersion" -> 4;
                                    default -> invoke(metaData, call, callArgs);
                                });
                    }
                    if (method.getName().equals("prepareStatement")
                            && args[0].toString().startsWith("show session variables")) {
                        if (failSettings) {
                            throw new SQLException("refused jdbc:mysql://h/db?user=app&password=hunter2");
                        }
                        return settingsStatement();
                    }
                    return invoke(real, method, args);
                });
    }

    private static PreparedStatement settingsStatement() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString("Variable_name")).thenReturn("wait_timeout", "max_connections");
        when(rows.getString("Value")).thenReturn("600", "151");
        return statement;
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
