package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * PostgreSQL's {@code log_min_duration_statement} is {@code -1}: no statement is ever logged for being slow. Judged
 * from the value the scan connection's session sees.
 */
final class PostgresSlowStatementLogRule extends AbstractServerSettingRule {

    static final String ID = "DB-SERVER-006";
    static final String SETTING = "log_min_duration_statement";

    PostgresSlowStatementLogRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "PostgreSQL does not log slow statements",
                        DatabaseAdvisorCategory.SERVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a PostgreSQL server with log_min_duration_statement = -1 (the default), so no"
                                + " statement is logged for its duration. Slow statements then leave no trace in the"
                                + " server log once they are gone. A value of 0 logs every statement and any other"
                                + " value is a threshold, so only -1 is reported. The value is the one the scan"
                                + " connection's own session sees. Another mechanism, such as auto_explain or a"
                                + " managed service's slow-query log, may cover the same need.",
                        "Set log_min_duration_statement to a threshold that matches the application's latency goal"
                                + " (for example 500 ms) so that slow statements are logged with their duration, or"
                                + " dismiss this finding when another slow-statement log is in place. The PostgreSQL"
                                + " panel's Statement ranking section shows the slowest statements while they are"
                                + " still in pg_stat_statements.",
                        "https://www.postgresql.org/docs/current/runtime-config-logging.html"
                                + "#GUC-LOG-MIN-DURATION-STATEMENT"),
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
        return number == null || number < -1 ? null : number == -1;
    }

    @Override
    String text(String dataSourceName, ServerSetting reported) {
        return "Datasource `" + dataSourceName + "`: " + SETTING + " is -1: no statement is logged for being slow.";
    }
}
