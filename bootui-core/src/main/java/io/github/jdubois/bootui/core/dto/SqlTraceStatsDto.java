package io.github.jdubois.bootui.core.dto;

/**
 * Aggregate counters over the SQL statements currently retained in the trace buffer.
 *
 * @param totalQueries number of captured executions in the buffer
 * @param totalDurationMillis sum of execution times across the buffer
 * @param maxDurationMillis slowest single execution time
 * @param avgDurationMillis mean execution time
 * @param slowQueries executions over the slow-query threshold
 * @param failedQueries executions that threw
 * @param selectCount {@code QUERY} executions
 * @param updateCount {@code UPDATE} executions
 * @param batchCount {@code BATCH} executions
 * @param otherCount executions of any other kind
 */
public record SqlTraceStatsDto(
        long totalQueries,
        long totalDurationMillis,
        long maxDurationMillis,
        double avgDurationMillis,
        long slowQueries,
        long failedQueries,
        long selectCount,
        long updateCount,
        long batchCount,
        long otherCount) {

    public static SqlTraceStatsDto empty() {
        return new SqlTraceStatsDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
