package io.github.jdubois.bootui.core.dto;

/**
 * A group of identical SQL statements, used to surface repeated queries and likely N+1 access
 * patterns.
 *
 * @param sql the (possibly truncated) statement text shared by the grouped executions
 * @param category coarse SQL category for the statement
 * @param executions number of buffered executions of this exact statement
 * @param totalElapsedMillis sum of execution times across the grouped executions
 * @param maxElapsedMillis slowest execution time within the group
 * @param potentialNPlusOne whether the repetition count suggests an N+1 access pattern
 */
public record SqlTraceGroupDto(
        String sql,
        String category,
        int executions,
        long totalElapsedMillis,
        long maxElapsedMillis,
        boolean potentialNPlusOne) {}
