package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the PostgreSQL panel: a bounded, read-only, on-demand read of the application's own
 * PostgreSQL database through its {@code pg_stat_*} and {@code pg_catalog} views.
 *
 * <p>It answers "what does the database itself say about its health", including work done by other clients,
 * which is what separates it from the Database Advisor (structural schema checks) and SQL Trace (what
 * <em>this</em> JVM executed).</p>
 *
 * <p>Everything the read could not establish is reported separately from the findings: {@link #databases()}
 * carries each datasource's status and per-section outcome, {@link #diagnostics()} carries the reasons, and
 * {@link #truncated()} says whether a bound cut the read short. None of them count as findings, so an
 * incomplete read is visible without being mistaken for a clean one.</p>
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
        int findingsFound,
        boolean truncated,
        List<PostgresDatabaseDto> databases,
        List<PostgresFindingDto> findings,
        List<PostgresSeverityCountDto> severityCounts,
        List<PostgresDiagnosticDto> diagnostics,
        AdvisorEvidenceDto evidence) {

    public PostgresInsightReport {
        evidence = evidence == null ? AdvisorEvidenceDto.unknown() : evidence;
        databases = DtoCollections.immutableCopy(databases);
        findings = DtoCollections.immutableCopy(findings);
        severityCounts = DtoCollections.immutableCopy(severityCounts);
        diagnostics = DtoCollections.immutableCopy(diagnostics);
    }
}
