package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.AbstractDriverRule.Outcome;

/** The applicability test the MySQL Connector/J rules share. */
final class MySqlConnectorJ {

    private MySqlConnectorJ() {}

    /**
     * {@code null} when {@code schema} is read through MySQL Connector/J and its settings can be judged; otherwise
     * the reason it is not, or {@link Outcome#notApplicable()} when it is simply another kind of datasource.
     */
    static Outcome applicability(SchemaSnapshot schema, DriverEvidence driver) {
        DriverSettingCatalog.Driver family = driver == null ? DriverSettingCatalog.Driver.UNKNOWN : driver.driver();
        if (family == DriverSettingCatalog.Driver.MYSQL_CONNECTOR_J) {
            return null;
        }
        if (!schema.dialect().isMySqlFamily() && family != DriverSettingCatalog.Driver.MARIADB_CONNECTOR_J) {
            return Outcome.notApplicable();
        }
        Outcome unidentified = AbstractDriverRule.driverUnidentified(driver);
        if (unidentified != null) {
            return unidentified;
        }
        return family == DriverSettingCatalog.Driver.MARIADB_CONNECTOR_J
                ? Outcome.skip("uses MariaDB Connector/J, which has a different bulk setting; not evaluated.")
                : Outcome.skip("uses the driver `" + DatabaseAdvisorRuleSupport.detail(driver.driverName())
                        + "`, not MySQL Connector/J; not evaluated.");
    }
}
