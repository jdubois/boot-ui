package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;

/**
 * MySQL Connector/J executes a JDBC batch one statement at a time unless {@code rewriteBatchedStatements} is true.
 * Judged only from configured evidence and only when Hibernate batching is demonstrably on for the datasource. MariaDB
 * Connector/J has a different bulk setting and is not evaluated; a MariaDB server reached through MySQL Connector/J is.
 */
final class MySqlRewriteBatchedStatementsRule extends AbstractDriverRule {

    MySqlRewriteBatchedStatementsRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        "DB-DRIVER-002",
                        "MySQL Connector/J does not rewrite batched statements",
                        DatabaseAdvisorCategory.DRIVER,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Detects a datasource using MySQL Connector/J where Hibernate JDBC batching is enabled"
                                + " (hibernate.jdbc.batch_size above 1) but the driver property"
                                + " rewriteBatchedStatements, whose default is false, is not true in the JDBC URL or the"
                                + " pool's driver properties. Without it Connector/J does not rewrite a batch of"
                                + " prepared INSERT or REPLACE statements into multi-values statements. Only configured"
                                + " evidence is read. A MariaDB server reached through MySQL Connector/J is included;"
                                + " MariaDB Connector/J is not evaluated.",
                        "Evaluate rewriteBatchedStatements=true in the JDBC URL or in the pool's data-source"
                                + " properties and measure. Connector/J documents that it enables multi-queries"
                                + " regardless of allowMultiQueries, which might allow SQL injection when plain"
                                + " statements are used with unsanitized input (Hibernate uses prepared statements)."
                                + " Rewritten batches can also report different update counts and generated keys:"
                                + " read the Connector/J documentation for rewritten INSERT ... ON DUPLICATE KEY"
                                + " UPDATE batches and for Statement.getGeneratedKeys() before enabling it.",
                        "https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html"),
                "No datasource using MySQL Connector/J was detected.");
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema) {
        DriverEvidence driver = schema.driver();
        Outcome applicability = MySqlConnectorJ.applicability(schema, driver);
        if (applicability != null) {
            return applicability;
        }
        Outcome gate = batchingGate(context, schema);
        if (gate != null) {
            return gate;
        }
        String name = DriverSettingCatalog.REWRITE_BATCHED_STATEMENTS;
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
