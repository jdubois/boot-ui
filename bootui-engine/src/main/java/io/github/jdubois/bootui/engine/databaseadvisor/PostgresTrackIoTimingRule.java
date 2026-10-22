package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * PostgreSQL's {@code track_io_timing} is {@code off}, so block read and write time is not measured. Judged from the
 * value the scan connection's session sees.
 */
final class PostgresTrackIoTimingRule extends AbstractServerSettingRule {

    static final String ID = "DB-SERVER-005";
    static final String SETTING = "track_io_timing";

    PostgresTrackIoTimingRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "PostgreSQL does not measure I/O time",
                        DatabaseAdvisorCategory.SERVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a PostgreSQL server with track_io_timing = off (the default), so the time spent"
                                + " reading and writing blocks is not recorded in pg_stat_database, pg_stat_statements"
                                + " or EXPLAIN (ANALYZE, BUFFERS). Without it a slow statement cannot be told apart"
                                + " as I/O-bound or CPU-bound from the server's own numbers. The value is the one the"
                                + " scan connection's own session sees. It is a review prompt: the timing adds a small"
                                + " overhead per block access, which is negligible on most current systems.",
                        "Enable track_io_timing (a reload is enough, no restart) after checking the clock overhead"
                                + " with pg_test_timing, so that the PostgreSQL panel's Statement ranking and Vital"
                                + " signs sections report I/O time.",
                        "https://www.postgresql.org/docs/current/runtime-config-statistics.html#GUC-TRACK-IO-TIMING"),
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
        return "Datasource `" + dataSourceName + "`: " + SETTING + " is off: block read and write time is not"
                + " measured.";
    }
}
