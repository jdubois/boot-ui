package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.ArrayList;
import java.util.List;

/**
 * Driver diagnostics that cost time or memory while on and are meant for a short investigation: Connector/J's
 * profiling, slow-query, performance-metrics, usage-advisor, protocol-trace and packet-debug settings, and pgjdbc's
 * {@code logUnclosedConnections}, which captures a stack trace at every connection opening. Each defaults to
 * {@code false}. Judged only from configured evidence, one finding per setting that is on.
 */
final class DriverDiagnosticSettingsRule extends AbstractDriverRule {

    DriverDiagnosticSettingsRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-DRIVER-007",
                        "JDBC driver diagnostic setting is enabled",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a datasource using MySQL Connector/J or the PostgreSQL JDBC driver (pgjdbc) where a"
                                + " diagnostic driver property is true in the JDBC URL or the pool's driver"
                                + " properties. For Connector/J: profileSQL, logSlowQueries, gatherPerfMetrics,"
                                + " useUsageAdvisor, traceProtocol, enablePacketDebug, autoGenerateTestcaseScript and"
                                + " explainSlowQueries. For pgjdbc: logUnclosedConnections, which captures a stack"
                                + " trace at each connection opening. They all default to false and add overhead while"
                                + " on. Only configured evidence is read. MariaDB Connector/J is not evaluated.",
                        "Turn the setting off once the investigation it was enabled for is over, or enable it only"
                                + " in an environment where its overhead and log volume do not matter.",
                        "https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-debugging-profiling.html"),
                "No datasource using MySQL Connector/J or the PostgreSQL JDBC driver (pgjdbc) was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        DriverEvidence driver = schema.driver();
        Outcome applicability = PgJdbc.pgjdbcOrConnectorJ(schema, driver);
        if (applicability != null) {
            return applicability;
        }
        List<String> names = driver.driver() == DriverSettingCatalog.Driver.PGJDBC
                ? List.of(DriverSettingCatalog.LOG_UNCLOSED_CONNECTIONS)
                : DriverSettingCatalog.MYSQL_DIAGNOSTIC_SETTINGS;
        List<Outcome> unresolved = new ArrayList<>();
        boolean found = false;
        for (String name : names) {
            BooleanSetting setting = BooleanSetting.of(driver, name);
            if (setting.unresolved() != null) {
                unresolved.add(setting.unresolved());
            } else if (setting.configuredTrue()) {
                findings.add(
                        "Datasource `" + schema.dataSourceName() + "`: `" + name + "` is "
                                + DriverRuleText.shown(setting.configured().value()) + ", configured in the "
                                + setting.configured().label()
                                + ", which adds overhead while on (driver default `false`). Evidence: configured.",
                        settingIdentity(schema.dataSourceName(), name));
                found = true;
            }
        }
        return conclude(found, unresolved);
    }
}
