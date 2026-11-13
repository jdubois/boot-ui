package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL Connector/J's {@code autoReconnect=true} and {@code autoReconnectForPools=true} properties are
 * explicitly discouraged by the Connector/J documentation because driver-level automatic reconnection can
 * corrupt session and transaction state and bypasses connection-pool validation.
 */
final class MySqlAutoReconnectRule extends AbstractDriverRule {

    static final String ID = "DB-DRIVER-010";

    private static final List<String> SETTINGS =
            List.of(DriverSettingCatalog.AUTO_RECONNECT, DriverSettingCatalog.AUTO_RECONNECT_FOR_POOLS);

    MySqlAutoReconnectRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "MySQL Connector/J autoReconnect is enabled",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Detects a datasource using MySQL Connector/J where autoReconnect or autoReconnectForPools is"
                                + " true in the JDBC URL or the pool's driver properties. Connector/J documents that"
                                + " \"the use of this feature is not recommended, because it has side effects related"
                                + " to session state and data consistency\" and can corrupt in-flight transactions"
                                + " while hiding dead connections from the connection pool. Only configured evidence"
                                + " is read. A MariaDB server reached through MySQL Connector/J is included; MariaDB"
                                + " Connector/J is not evaluated.",
                        "Remove autoReconnect and autoReconnectForPools (both default to false) and let the connection"
                                + " pool (HikariCP or Agroal) validate, evict and replace broken connections.",
                        "https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-high-availability-and-clustering.html"),
                "No datasource using MySQL Connector/J was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        DriverEvidence driver = schema.driver();
        Outcome applicability = MySqlConnectorJ.applicability(schema, driver);
        if (applicability != null) {
            return applicability;
        }
        List<Outcome> unresolved = new ArrayList<>();
        boolean found = false;
        for (String name : SETTINGS) {
            BooleanSetting setting = BooleanSetting.of(driver, name);
            if (setting.unresolved() != null) {
                unresolved.add(setting.unresolved());
            } else if (setting.configuredTrue()) {
                findings.add(
                        "Datasource `" + schema.dataSourceName() + "`: `" + name + "` is "
                                + DriverRuleText.shown(setting.configured().value()) + ", configured in the "
                                + setting.configured().label()
                                + ", which Connector/J discourages because automatic reconnection can corrupt session"
                                + " and transaction state (driver default `false`). Evidence: configured.",
                        settingIdentity(schema.dataSourceName(), name));
                found = true;
            }
        }
        return conclude(found, unresolved);
    }
}
