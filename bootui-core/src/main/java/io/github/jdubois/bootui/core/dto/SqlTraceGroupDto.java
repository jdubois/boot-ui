package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A group of identical SQL statements captured by the SQL Trace panel, used to surface repeated
 * queries and likely N+1 access patterns.
 *
 * @param sql the (possibly truncated) statement text shared by the grouped executions
 * @param category coarse SQL category for the statement
 * @param executions number of buffered executions of this exact statement
 * @param totalDurationMillis sum of execution times across the grouped executions
 * @param maxDurationMillis slowest execution time within the group
 * @param potentialNPlusOne whether the repetition count suggests an N+1 access pattern
 * @param callSites distinct call sites observed for this group's executions, most-recently-seen first
 *     and bounded to a handful of entries; empty when call-site capture is disabled or no application
 *     frame was found for any execution in the group
 */
public record SqlTraceGroupDto(
        String sql,
        String category,
        long executions,
        long totalDurationMillis,
        long maxDurationMillis,
        boolean potentialNPlusOne,
        List<String> callSites) {

    public SqlTraceGroupDto {
        callSites = callSites == null ? List.of() : List.copyOf(callSites);
    }
}
