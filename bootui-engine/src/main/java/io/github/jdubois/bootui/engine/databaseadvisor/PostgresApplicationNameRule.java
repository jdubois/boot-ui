package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * pgjdbc reports the application name {@code PostgreSQL JDBC Driver} unless {@code ApplicationName} is configured, so
 * every connection of every application looks the same in {@code pg_stat_activity}, which BootUI's PostgreSQL panel
 * reads. Judged only from configured evidence (the JDBC URL and the pool's driver properties); no query is run. The
 * configured name is never echoed: only whether it is missing, blank or the driver default is said.
 */
final class PostgresApplicationNameRule extends AbstractDriverRule {

    PostgresApplicationNameRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-DRIVER-009",
                        "PostgreSQL connections do not carry an application name",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a datasource using the PostgreSQL JDBC driver (pgjdbc) whose driver property"
                                + " ApplicationName is not configured in the JDBC URL or the pool's driver properties,"
                                + " is blank, or equals the driver default \""
                                + DriverSettingCatalog.PGJDBC_DEFAULT_APPLICATION_NAME
                                + "\". Connections then show that default name in pg_stat_activity and cannot be told"
                                + " apart from other applications'. Only configured evidence is read: settings the"
                                + " driver takes from elsewhere are not visible, and no query is run. Another"
                                + " PostgreSQL JDBC driver is not evaluated.",
                        "Set ApplicationName in the JDBC URL or in the pool's data-source properties (for HikariCP,"
                                + " spring.datasource.hikari.data-source-properties.ApplicationName) to a name that"
                                + " identifies the application, so its sessions can be found in pg_stat_activity and"
                                + " the server logs.",
                        "https://jdbc.postgresql.org/documentation/use/"),
                "No PostgreSQL datasource using the PostgreSQL JDBC driver (pgjdbc) was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        DriverEvidence driver = schema.driver();
        Outcome applicability = PgJdbc.applicability(schema, driver);
        if (applicability != null) {
            return applicability;
        }
        String name = DriverSettingCatalog.APPLICATION_NAME;
        SettingReading reading = SettingReading.of(driver, name);
        if (reading.unresolved() != null) {
            return reading.unresolved();
        }
        String state;
        String effect = "so connections report the name `" + DriverSettingCatalog.PGJDBC_DEFAULT_APPLICATION_NAME
                + "` in pg_stat_activity";
        if (reading.configured() == null) {
            state = "is not configured in the JDBC URL or the pool's driver properties";
        } else if (reading.configured().value().isBlank()
                && reading.configured().value().length() < DriverSettingCatalog.MAX_VALUE_LENGTH) {
            state = "is blank, configured in the " + reading.configured().label();
            effect = "so connections carry no application name in pg_stat_activity";
        } else if (DriverSettingCatalog.PGJDBC_DEFAULT_APPLICATION_NAME.equals(
                reading.configured().value())) {
            state = "is set to the driver default, configured in the "
                    + reading.configured().label();
        } else {
            return Outcome.assessed();
        }
        findings.add(
                "Datasource `" + schema.dataSourceName() + "`: `" + name + "` " + state + ", " + effect
                        + ". Evidence: configured.",
                settingIdentity(schema.dataSourceName(), name));
        return Outcome.assessed();
    }
}
