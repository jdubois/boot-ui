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
 * @param batchExecutions executions that ran as a JDBC batch
 * @param selectCount {@code SELECT} executions
 * @param insertCount {@code INSERT} executions
 * @param updateCount {@code UPDATE} executions
 * @param deleteCount {@code DELETE} executions
 * @param otherCount executions of any other category (including DDL)
 * @param evicted executions dropped from the buffer because it reached capacity
 */
public record SqlTraceStatsDto(
        long totalQueries,
        long totalDurationMillis,
        long maxDurationMillis,
        double avgDurationMillis,
        long slowQueries,
        long failedQueries,
        long batchExecutions,
        long selectCount,
        long insertCount,
        long updateCount,
        long deleteCount,
        long otherCount,
        long evicted) {

    public static SqlTraceStatsDto empty() {
        return new SqlTraceStatsDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
