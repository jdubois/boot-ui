package io.github.jdubois.bootui.core.dto;

/**
 * One normalized statement ranked by {@code pg_stat_statements}.
 *
 * <p>PostgreSQL normalizes the statement text itself — literals are already replaced by placeholders before
 * BootUI ever sees the row — and the text is additionally masked and truncated on the way out.</p>
 *
 * @param queryId the server-assigned normalized query id, as text (it does not fit a JSON-safe integer)
 * @param query the normalized statement text, masked and truncated
 * @param calls how many times the statement was executed since the last statistics reset
 * @param totalTimeMs total execution time across all calls
 * @param meanTimeMs mean execution time per call
 * @param rows total rows returned or affected
 * @param cacheHitRatio shared-block hit ratio for this statement, in {@code [0,1]}, or {@code null}
 */
public record PostgresStatementDto(
        String queryId,
        String query,
        Long calls,
        Double totalTimeMs,
        Double meanTimeMs,
        Double maxTimeMs,
        Long rows,
        Double cacheHitRatio) {}
