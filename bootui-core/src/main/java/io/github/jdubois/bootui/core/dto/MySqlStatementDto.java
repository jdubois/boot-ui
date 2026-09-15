package io.github.jdubois.bootui.core.dto;

/** Only normalized DIGEST_TEXT is eligible for display; timings are milliseconds, counters exact strings. */
public record MySqlStatementDto(
        String digest,
        String schemaName,
        String digestText,
        String calls,
        Double totalTimeMs,
        Double averageTimeMs,
        Double maxTimeMs,
        String rowsExamined,
        String rowsSent,
        String errors,
        String temporaryTables,
        String temporaryDiskTables) {}
