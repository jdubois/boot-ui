package io.github.jdubois.bootui.core.dto;

/**
 * Aggregate statistics computed over the currently buffered SQL executions.
 *
 * @param recorded number of executions currently held in the buffer
 * @param captured total executions seen since the recorder last started or was cleared
 * @param evicted executions dropped from the buffer because it reached capacity
 * @param totalElapsedMillis sum of execution times across the buffered executions
 * @param maxElapsedMillis slowest buffered execution time
 * @param avgElapsedMillis mean execution time across the buffered executions
 * @param slowQueries number of buffered executions that reached the slow-query threshold
 * @param failedQueries number of buffered executions that failed
 * @param batchExecutions number of buffered executions that were batched
 * @param selectCount number of buffered {@code SELECT} executions
 * @param insertCount number of buffered {@code INSERT} executions
 * @param updateCount number of buffered {@code UPDATE} executions
 * @param deleteCount number of buffered {@code DELETE} executions
 * @param otherCount number of buffered executions in any other category
 */
public record SqlTraceStatsDto(
        int recorded,
        long captured,
        long evicted,
        long totalElapsedMillis,
        long maxElapsedMillis,
        double avgElapsedMillis,
        int slowQueries,
        int failedQueries,
        int batchExecutions,
        int selectCount,
        int insertCount,
        int updateCount,
        int deleteCount,
        int otherCount) {}
