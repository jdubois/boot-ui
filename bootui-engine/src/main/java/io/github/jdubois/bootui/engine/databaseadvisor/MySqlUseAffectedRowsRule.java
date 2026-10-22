package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * MySQL Connector/J's {@code useAffectedRows=true} drops the {@code CLIENT_FOUND_ROWS} flag, so DML reports changed
 * rows rather than matched rows. Judged only from configured evidence; it needs no batching gate because the setting
 * changes update counts for every statement.
 */
final class MySqlUseAffectedRowsRule extends AbstractDriverRule {

    MySqlUseAffectedRowsRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-DRIVER-003",
                        "MySQL Connector/J reports affected rows instead of found rows",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Detects a datasource using MySQL Connector/J whose driver property useAffectedRows is true"
                                + " in the JDBC URL or the pool's driver properties. Connector/J documents that this"
                                + " does not set the CLIENT_FOUND_ROWS flag, \"is not JDBC-compliant and it will break"
                                + " most applications that rely on 'found' rows vs. 'affected rows' for DML"
                                + " statements\". An UPDATE that writes the values a row already holds then reports 0"
                                + " rows, which an ORM can read as a lost update (an optimistic-lock or stale-state"
                                + " failure). Only configured evidence is read. A MariaDB server reached through MySQL"
                                + " Connector/J is included; MariaDB Connector/J is not evaluated.",
                        "Remove useAffectedRows (its default is false) unless the application deliberately depends on"
                                + " affected-row counts, for example for INSERT ... ON DUPLICATE KEY UPDATE, and then"
                                + " verify how its ORM treats an update count of 0.",
                        "https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-connection.html"),
                "No datasource using MySQL Connector/J was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema) {
        DriverEvidence driver = schema.driver();
        Outcome applicability = MySqlConnectorJ.applicability(schema, driver);
        if (applicability != null) {
            return applicability;
        }
        String name = DriverSettingCatalog.USE_AFFECTED_ROWS;
        BooleanSetting setting = BooleanSetting.of(driver, name);
        if (setting.unresolved() != null) {
            return setting.unresolved();
        }
        if (!setting.configuredTrue()) {
            return Outcome.pass();
        }
        return Outcome.finding("Datasource `" + schema.dataSourceName() + "`: `" + name + "` is "
                + DriverRuleText.shown(setting.configured().value()) + ", configured in the "
                + setting.configured().label()
                + ", so DML reports changed rows instead of matched rows. Evidence: configured.");
    }
}
