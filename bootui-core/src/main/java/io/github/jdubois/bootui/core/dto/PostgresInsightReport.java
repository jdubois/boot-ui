package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the PostgreSQL panel: a bounded, read-only, on-demand read of the application's own
 * PostgreSQL database through its {@code pg_stat_*} and {@code pg_catalog} views.
 *
 * <p>It is a runtime view, not an advisor. It carries no findings, no severities and no score: it shows what
 * the database itself currently reports — its live sessions, its slowest statements, its index and relation
 * activity, its autovacuum state, its replication and its operational settings — and leaves the reading to
 * the developer. That also covers work done by other clients of the same database, which is what separates
 * it from the Database Advisor (structural schema checks) and SQL Trace (what <em>this</em> JVM executed).</p>
 *
 * <p>Everything the read could not establish is reported explicitly: {@link #databases()} carries each
 * datasource's status and per-section outcome, {@link #diagnostics()} carries the reasons,
 * {@link #limitations()} summarises what the numbers therefore do not cover, and {@link #truncated()} says
 * whether a bound cut the read short. An incomplete read stays visible instead of passing for a complete
 * one.</p>
 *
 * @param status {@code NOT_READ}, {@code READ}, {@code PARTIAL}, {@code ERROR} or {@code DISABLED}
 * @param readAt epoch milliseconds of the read, or {@code null} when it has not run
 */
public record PostgresInsightReport(
        boolean localOnly,
        String disclaimer,
        String status,
        String message,
        Long readAt,
        int databasesRead,
        boolean truncated,
        List<PostgresDatabaseDto> databases,
        List<PostgresDiagnosticDto> diagnostics,
        List<String> limitations) {

    public PostgresInsightReport {
        databases = DtoCollections.immutableCopy(databases);
        diagnostics = DtoCollections.immutableCopy(diagnostics);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
