package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.AbstractDriverRule.Outcome;

/** The applicability tests the PostgreSQL JDBC driver (pgjdbc) rules share. */
final class PgJdbc {

    private PgJdbc() {}

    /**
     * {@code null} when {@code schema} is a PostgreSQL datasource read through pgjdbc and its settings can be judged;
     * otherwise the reason it is not, or {@link Outcome#notApplicable()} when it is simply another kind of datasource.
     * Another PostgreSQL driver is skipped: its properties are not assumed to match pgjdbc's.
     */
    static Outcome applicability(SchemaSnapshot schema, DriverEvidence driver) {
        if (schema.dialect() != Dialect.POSTGRESQL) {
            return Outcome.notApplicable();
        }
        Outcome unidentified = AbstractDriverRule.driverUnidentified(driver);
        if (unidentified != null) {
            return unidentified;
        }
        if (driver.driver() != DriverSettingCatalog.Driver.PGJDBC) {
            return Outcome.skip("uses the driver `" + DatabaseAdvisorRuleSupport.detail(driver.driverName())
                    + "`, not the PostgreSQL JDBC driver (pgjdbc); not evaluated.");
        }
        return null;
    }

    /** {@code null} when {@code schema} is read through pgjdbc or MySQL Connector/J; otherwise as above. */
    static Outcome pgjdbcOrConnectorJ(SchemaSnapshot schema, DriverEvidence driver) {
        return schema.dialect() == Dialect.POSTGRESQL
                ? applicability(schema, driver)
                : MySqlConnectorJ.applicability(schema, driver);
    }
}
