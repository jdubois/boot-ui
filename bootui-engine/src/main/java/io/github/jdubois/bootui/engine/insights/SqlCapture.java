package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;

/**
 * Whether this application's SQL can be recorded at all, which the {@code sql} and {@code connection} sources of the
 * runtime journal depend on: BootUI records JDBC statements through a traced {@code DataSource} (and, on Quarkus,
 * Hibernate ORM's statement inspector), never R2DBC or a reactive SQL client. Without it, a check that reads only SQL
 * would run over nothing and report no finding, which must never read as a healthy result ({@code docs/PLAN-v2.md}
 * §5.5).
 *
 * @param recorded whether statements can be recorded
 * @param reason why they cannot, when not recorded; otherwise what they still miss, such as R2DBC statements beside a
 *     traced JDBC pool, or {@code null}
 */
public record SqlCapture(boolean recorded, String reason) {

    /** Why statements are not recorded when no JDBC {@code DataSource} is traced. */
    public static final String NOT_RECORDED = "This application's database access is not recorded: BootUI records JDBC"
            + " statements through a traced DataSource, not R2DBC or reactive SQL clients, and no traced DataSource"
            + " is in use.";

    /** Why statements are not recorded when the application reaches its database through R2DBC only. */
    public static final String R2DBC_ONLY = "This application's database access is not recorded: it uses R2DBC, and"
            + " BootUI records JDBC statements through a traced DataSource, not R2DBC.";

    /** Why statements are not recorded when SQL capture is turned off. */
    public static final String DISABLED =
            "SQL capture is disabled (bootui.sql-trace.enabled=false), so no statement is recorded.";

    /** What a traced JDBC pool misses when the application also reads through R2DBC. */
    public static final String R2DBC_NOT_RECORDED =
            "This application also uses R2DBC, whose statements BootUI does not record: only JDBC statements are"
                    + " counted.";

    /** Statements are recorded. */
    public static SqlCapture capturing() {
        return new SqlCapture(true, null);
    }

    /** Statements are recorded, but {@code limitation} names what they miss. */
    public static SqlCapture recordedExcept(String limitation) {
        return new SqlCapture(true, limitation);
    }

    /** Statements are not recorded, for {@code reason}. */
    public static SqlCapture notRecorded(String reason) {
        return new SqlCapture(false, reason == null ? NOT_RECORDED : reason);
    }

    /**
     * What an adapter's {@link SqlTraceRecorder} records.
     *
     * @param recorder the recorder, or {@code null} when the adapter created none, as without a JDBC data source
     * @param tracedDataSourceRequired whether a {@code DataSource} must have been traced, as on Spring, where each
     *     {@code DataSource} bean is wrapped when created; on Quarkus the recorder exists only with a JDBC data source,
     *     and Hibernate ORM's statements reach it without one being wrapped
     * @param r2dbc whether the application also has an R2DBC connection factory
     */
    public static SqlCapture of(SqlTraceRecorder recorder, boolean tracedDataSourceRequired, boolean r2dbc) {
        if (recorder != null && !recorder.isEnabled()) {
            return notRecorded(DISABLED);
        }
        if (recorder == null || (tracedDataSourceRequired && !recorder.hasWrappedDataSource())) {
            return notRecorded(r2dbc ? R2DBC_ONLY : NOT_RECORDED);
        }
        return r2dbc ? recordedExcept(R2DBC_NOT_RECORDED) : capturing();
    }
}
