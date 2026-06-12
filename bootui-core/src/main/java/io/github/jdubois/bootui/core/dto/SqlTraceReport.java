package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level SQL Trace report returned by {@code GET /bootui/api/sql-trace}.
 *
 * @param available whether SQL tracing is active (a {@code DataSource} was wrapped)
 * @param unavailableReason human-readable reason when {@code available} is {@code false}
 * @param recording whether new executions are currently being recorded
 * @param captureParameters whether bound parameter values are being captured
 * @param maxQueries effective ring-buffer capacity
 * @param slowQueryThresholdMillis threshold (ms) above which an execution is flagged slow
 * @param dataSources names of the {@code DataSource} beans being traced
 * @param stats aggregate statistics over the buffered executions
 * @param queries buffered executions, most recent first
 * @param topStatements statements grouped by exact text, most frequent first
 * @param warnings non-fatal advisories about the current trace state
 */
public record SqlTraceReport(
        boolean available,
        String unavailableReason,
        boolean recording,
        boolean captureParameters,
        int maxQueries,
        long slowQueryThresholdMillis,
        List<String> dataSources,
        SqlTraceStatsDto stats,
        List<SqlTraceQueryDto> queries,
        List<SqlTraceGroupDto> topStatements,
        List<String> warnings) {}
