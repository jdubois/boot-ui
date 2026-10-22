package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * PostgreSQL's {@code autovacuum} is {@code off}. The anti-wraparound vacuum still runs, but routine vacuum and
 * analyze never do. Judged from the value the scan connection's session sees.
 */
final class PostgresAutovacuumDisabledRule extends AbstractServerSettingRule {

    static final String ID = "DB-SERVER-003";
    static final String SETTING = "autovacuum";

    PostgresAutovacuumDisabledRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "PostgreSQL autovacuum is disabled",
                        DatabaseAdvisorCategory.SERVER,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Detects a PostgreSQL server with autovacuum = off. The autovacuum launcher still starts"
                                + " anti-wraparound vacuums to prevent transaction ID wraparound, but routine vacuum"
                                + " and analyze never run on their own, so dead tuples accumulate and planner"
                                + " statistics go stale unless something else vacuums and analyzes the tables. It can"
                                + " be deliberate for a bulk load, or when an external scheduler vacuums. A table"
                                + " can still enable autovacuum through its own storage parameter. The value is the one"
                                + " the scan connection's own session sees.",
                        "Turn autovacuum back on (autovacuum = on, a reload is enough) unless vacuum and analyze are"
                                + " scheduled by other means, in which case dismiss this finding. The PostgreSQL"
                                + " panel's Autovacuum health section shows dead tuples and the last (auto)vacuum"
                                + " and analyze per table.",
                        "https://www.postgresql.org/docs/current/runtime-config-autovacuum.html#GUC-AUTOVACUUM"),
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
        Boolean enabled = ServerSettings.bool(value);
        return enabled == null ? null : !enabled;
    }

    @Override
    String text(String dataSourceName, ServerSetting reported) {
        return "Datasource `" + dataSourceName + "`: " + SETTING + " is off: only the anti-wraparound vacuum runs;"
                + " routine vacuum and analyze never do.";
    }
}
