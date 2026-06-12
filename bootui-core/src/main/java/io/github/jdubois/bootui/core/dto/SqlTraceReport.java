package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level SQL trace report returned by the SQL Trace panel.
 *
 * @param available whether a {@code DataSource} was wrapped and is being traced
 * @param unavailableReason populated when {@code available} is {@code false}
 * @param capturing whether new executions are currently being recorded (runtime pause/resume)
 * @param captureParameters whether parameter bindings are being captured
 * @param bufferSize maximum number of executions retained in memory
 * @param totalCaptured total executions seen since startup (may exceed buffer)
 * @param slowQueryThresholdMillis threshold above which an execution is "slow"
 * @param dataSources names of the {@code DataSource} beans being traced
 * @param stats aggregate counters over the retained buffer
 * @param entries the retained executions, most recent first
 * @param topStatements statements grouped by exact text, most frequent first
 * @param warnings non-fatal advisories about the current trace state
 */
public record SqlTraceReport(
        boolean available,
        String unavailableReason,
        boolean capturing,
        boolean captureParameters,
        int bufferSize,
        long totalCaptured,
        long slowQueryThresholdMillis,
        List<String> dataSources,
        SqlTraceStatsDto stats,
        List<SqlTraceEntryDto> entries,
        List<SqlTraceGroupDto> topStatements,
        List<String> warnings) {

    public SqlTraceReport {
        dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
        entries = entries == null ? List.of() : List.copyOf(entries);
        topStatements = topStatements == null ? List.of() : List.copyOf(topStatements);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static SqlTraceReport unavailable(String reason) {
        return new SqlTraceReport(
                false,
                reason,
                false,
                false,
                0,
                0,
                0,
                List.of(),
                SqlTraceStatsDto.empty(),
                List.of(),
                List.of(),
                List.of());
    }
}
