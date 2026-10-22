package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * PostgreSQL's {@code idle_in_transaction_session_timeout} is {@code 0}: a session left idle inside a transaction is
 * never terminated, so it keeps its locks and holds back vacuum. Judged from the value the scan connection's session
 * sees.
 */
final class PostgresIdleInTransactionTimeoutRule extends AbstractServerSettingRule {

    static final String ID = "DB-SERVER-001";
    static final String SETTING = "idle_in_transaction_session_timeout";

    PostgresIdleInTransactionTimeoutRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "PostgreSQL does not end sessions that are idle in a transaction",
                        DatabaseAdvisorCategory.SERVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a PostgreSQL server whose idle_in_transaction_session_timeout is 0 (the default),"
                                + " so a session that stays idle inside an open transaction is never terminated. Such a"
                                + " session keeps its locks, and an open transaction prevents vacuuming away recently"
                                + " dead tuples. The value is the one the scan connection's own session sees: a pool"
                                + " init statement or a driver option can give pooled sessions another, and a role or"
                                + " database setting may override it. It is a review prompt, not a defect: a server"
                                + " that only runs trusted, short transactions can leave it at 0.",
                        "Set idle_in_transaction_session_timeout to a value above the longest legitimate pause"
                                + " between two statements of a transaction (for example 60 seconds), on the role or"
                                + " database the application connects as, so that a forgotten transaction cannot hold"
                                + " locks indefinitely. The PostgreSQL panel's Sessions section lists the sessions that"
                                + " are idle in a transaction right now.",
                        "https://www.postgresql.org/docs/current/runtime-config-client.html"
                                + "#GUC-IDLE-IN-TRANSACTION-SESSION-TIMEOUT"),
                true,
                SETTING,
                9,
                6);
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
        return "Datasource `" + dataSourceName + "`: " + SETTING + " is 0 (disabled): a session idle in a transaction"
                + " is never ended, keeps its locks and blocks vacuum.";
    }
}
