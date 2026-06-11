package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level SQL trace report returned by the SQL Trace panel.
 *
 * @param available whether a {@code DataSource} is present and traceable
 * @param capturing whether the JDBC tracing proxy is actively recording
 * @param captureParameters whether parameter bindings are being captured
 * @param bufferSize maximum number of executions retained in memory
 * @param totalCaptured total executions seen since startup (may exceed buffer)
 * @param slowQueryThresholdMillis threshold above which an execution is "slow"
 * @param stats aggregate counters over the retained buffer
 * @param entries the retained executions, most recent first
 * @param unavailableReason populated when {@code available} is {@code false}
 */
public record SqlTraceReport(
        boolean available,
        boolean capturing,
        boolean captureParameters,
        int bufferSize,
        long totalCaptured,
        long slowQueryThresholdMillis,
        SqlTraceStatsDto stats,
        List<SqlTraceEntryDto> entries,
        String unavailableReason) {

    public SqlTraceReport {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static SqlTraceReport unavailable(String reason) {
        return new SqlTraceReport(false, false, false, 0, 0, 0, SqlTraceStatsDto.empty(), List.of(), reason);
    }
}
