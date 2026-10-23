package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * PostgreSQL's {@code statement_timeout} is {@code 0}: a runaway statement is never aborted by the server. Judged from
 * the value the scan connection's session sees.
 */
final class PostgresStatementTimeoutRule extends AbstractServerSettingRule {

    static final String ID = "DB-SERVER-002";
    static final String SETTING = "statement_timeout";

    PostgresStatementTimeoutRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "PostgreSQL has no statement timeout",
                        DatabaseAdvisorCategory.SERVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a PostgreSQL server whose statement_timeout is 0 (the default), so a statement that"
                                + " runs away is never aborted by the server and keeps its connection, locks and"
                                + " resources for as long as it runs. The value is the one the scan connection's own"
                                + " session sees: a pool init statement or a driver option can give pooled sessions"
                                + " another, and a role or database setting may override it. It is a review prompt,"
                                + " not a defect: a role that runs long reports or migrations needs no limit.",
                        "Set a statement_timeout on the role or database that serves interactive requests, well above"
                                + " the slowest legitimate statement, and keep the unlimited default for batch roles."
                                + " The PostgreSQL panel's Statement ranking section (from pg_stat_statements) shows"
                                + " which statements run longest.",
                        "https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-STATEMENT-TIMEOUT"),
                true,
                SETTING,
                0,
                0);
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        return judge(schema, findings);
    }

    @Override
    Boolean flagged(String value) {
        Long number = ServerSettings.number(value);
        return number == null || number < 0 ? null : number == 0;
    }

    @Override
    String text(String dataSourceName, ServerSetting reported) {
        return "Datasource `" + dataSourceName + "`: " + SETTING + " is 0 (disabled): the server never aborts a"
                + " runaway statement.";
    }
}
