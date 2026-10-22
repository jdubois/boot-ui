package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * Neither pgjdbc nor MySQL Connector/J sets a network read timeout by default, so a thread can wait indefinitely on a
 * connection that was silently dropped. The {@code socketTimeout} driver connection property is judged only from
 * configured evidence, in the unit its driver counts (pgjdbc seconds, Connector/J milliseconds). It is deliberately
 * not read from {@code Connection#getNetworkTimeout()}, which is mutable per-connection state a pool may set and reset
 * and says nothing about the driver property.
 */
final class DriverSocketTimeoutRule extends AbstractDriverRule {

    DriverSocketTimeoutRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-DRIVER-008",
                        "JDBC driver has no socket timeout",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a datasource using MySQL Connector/J or the PostgreSQL JDBC driver (pgjdbc) whose"
                                + " driver property socketTimeout is absent or 0 in the JDBC URL and the pool's driver"
                                + " properties. The default of both drivers is 0, which means no timeout, so a thread"
                                + " can wait indefinitely on a connection that was silently dropped. pgjdbc counts the"
                                + " value in seconds and Connector/J in milliseconds. Only the configured driver"
                                + " property is judged: settings supplied through other driver mechanisms (a service"
                                + " or configuration file, a data-source class) are not visible, and the JDBC"
                                + " network-timeout state of a live connection is not read. MariaDB Connector/J is not"
                                + " evaluated.",
                        "Evaluate a socketTimeout in the JDBC URL or in the pool's data-source properties that is"
                                + " longer than your slowest legitimate statement (pgjdbc: seconds; Connector/J:"
                                + " milliseconds), so a dead connection fails instead of blocking, and measure.",
                        "https://jdbc.postgresql.org/documentation/use/"),
                "No datasource using MySQL Connector/J or the PostgreSQL JDBC driver (pgjdbc) was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        DriverEvidence driver = schema.driver();
        Outcome applicability = PgJdbc.pgjdbcOrConnectorJ(schema, driver);
        if (applicability != null) {
            return applicability;
        }
        String name = DriverSettingCatalog.SOCKET_TIMEOUT;
        SettingReading reading = SettingReading.of(driver, name);
        if (reading.unresolved() != null) {
            return reading.unresolved();
        }
        boolean pgjdbc = driver.driver() == DriverSettingCatalog.Driver.PGJDBC;
        String unit = pgjdbc ? "seconds" : "milliseconds";
        String state;
        if (reading.configured() == null) {
            state = "is not configured in the JDBC URL or pool properties";
        } else {
            Long millis = DriverSettingCatalog.socketTimeoutMillis(
                    driver.driver(), reading.configured().value());
            if (millis == null) {
                return reading.unreadable(name, "an integer number of " + unit).unresolved();
            }
            if (millis > 0) {
                return Outcome.assessed();
            }
            state = "is "
                    + DriverSettingCatalog.asInteger(
                            driver.driver(), reading.configured().value()) + " in the "
                    + reading.configured().label();
        }
        findings.add(
                "Datasource `" + schema.dataSourceName() + "`: `" + name + "` " + state + " (0 means no"
                        + " timeout; unit: " + unit + "), so a dropped connection can block a thread forever."
                        + " Evidence: configured.",
                settingIdentity(schema.dataSourceName(), name));
        return Outcome.assessed();
    }
}
