package io.github.jdubois.bootui.core.dto;

/**
 * One datasource the Database Advisor tried to introspect, and how far it got.
 *
 * <p>A datasource that could not be read is reported explicitly rather than disappearing from the report:
 * "we could not read this database" must never look like "this database is clean".</p>
 *
 * @param name the adapter-reported datasource name
 * @param product the database product and version, or {@code null} when it could not be read
 * @param dialect the detected dialect family label (e.g. {@code PostgreSQL}, {@code MariaDB})
 * @param identifierCase how the server stores unquoted identifiers ({@code UPPER}, {@code LOWER},
 *     {@code MIXED}), or {@code null} when the driver could not report it — the reason a mapped name that
 *     "looks right" can still fail to match
 * @param status {@code AVAILABLE}, {@code PARTIAL} or {@code FAILED}
 * @param message the failure or partial-read reason, already masked and truncated; {@code null} when clean
 * @param tablesAnalyzed how many tables were actually analyzed for this datasource
 * @param truncated whether a scan bound stopped the introspection short
 */
public record DatabaseAdvisorDataSourceDto(
        String name,
        String product,
        String dialect,
        String identifierCase,
        String status,
        String message,
        int tablesAnalyzed,
        boolean truncated) {}
