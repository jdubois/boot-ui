package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;

/**
 * pgjdbc sends one row per statement inside a JDBC batch unless {@code reWriteBatchedInserts} is true; enabling
 * Hibernate batching alone does not change that. Judged only from configured evidence (JDBC URL and pool driver
 * properties) and only when Hibernate batching is demonstrably on for the datasource.
 */
final class PostgresRewriteBatchedInsertsRule extends AbstractDriverRule {

    PostgresRewriteBatchedInsertsRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-DRIVER-001",
                        "PostgreSQL driver does not rewrite batched inserts",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Detects a datasource using the PostgreSQL JDBC driver (pgjdbc) where Hibernate JDBC batching"
                                + " is enabled (hibernate.jdbc.batch_size above 1) but the driver property"
                                + " reWriteBatchedInserts, whose default is false, is not true in the JDBC URL or the"
                                + " pool's driver properties. Without it each batched insert is still sent as its own"
                                + " one-row statement. Only configured evidence is read: settings the driver takes from"
                                + " elsewhere (a service file, driverconfig.properties) are not visible.",
                        "Set reWriteBatchedInserts=true in the JDBC URL or in the pool's data-source properties (for"
                                + " HikariCP, spring.datasource.hikari.data-source-properties.reWriteBatchedInserts)"
                                + " and measure: pgjdbc documents that it turns batched inserts into multi-row"
                                + " inserts, a 2-3x improvement. It affects INSERT statements only, and Hibernate"
                                + " cannot batch inserts of entities that use GenerationType.IDENTITY (see"
                                + " HIB-ID-006).",
                        "https://jdbc.postgresql.org/documentation/use/"),
                "No PostgreSQL datasource using the PostgreSQL JDBC driver (pgjdbc) was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema) {
        if (schema.dialect() != Dialect.POSTGRESQL) {
            return Outcome.notApplicable();
        }
        DriverEvidence driver = schema.driver();
        Outcome unidentified = driverUnidentified(driver);
        if (unidentified != null) {
            return unidentified;
        }
        if (driver.driver() != DriverSettingCatalog.Driver.PGJDBC) {
            return Outcome.skip("uses the driver `" + DatabaseAdvisorRuleSupport.detail(driver.driverName())
                    + "`, not the PostgreSQL JDBC driver (pgjdbc); not evaluated.");
        }
        Outcome gate = batchingGate(context, schema);
        if (gate != null) {
            return gate;
        }
        String name = DriverSettingCatalog.RE_WRITE_BATCHED_INSERTS;
        BooleanSetting setting = BooleanSetting.of(driver, name);
        if (setting.unresolved() != null) {
            return setting.unresolved();
        }
        if (setting.configuredTrue()) {
            return Outcome.pass();
        }
        JdbcBatching batching = context.jdbcBatchingOf(schema.dataSourceName());
        String state = setting.configured() == null
                ? "is not set in the JDBC URL or the pool's driver properties (driver default `false`)"
                : "is " + DriverRuleText.shown(setting.configured().value()) + ", configured in the "
                        + setting.configured().label();
        return Outcome.finding("Datasource `" + schema.dataSourceName() + "`: `" + name + "` " + state
                + " while Hibernate batches writes (batch size " + batching.batchSize() + "). Evidence: configured.");
    }
}
