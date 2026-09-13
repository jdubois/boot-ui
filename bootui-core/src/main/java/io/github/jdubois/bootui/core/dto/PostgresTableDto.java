package io.github.jdubois.bootui.core.dto;

/**
 * One relation's size and access shape, from {@code pg_stat_user_tables} and {@code pg_total_relation_size}.
 *
 * <p>A large table with a high {@link #sequentialScanRatio()} is a missing-index radar, not a finding on its
 * own: a small, fully cached table is correctly seq-scanned.</p>
 */
public record PostgresTableDto(
        String schema,
        String table,
        Long totalSizeBytes,
        Long tableSizeBytes,
        Long indexSizeBytes,
        Long liveTuples,
        Long deadTuples,
        Long sequentialScans,
        Long sequentialTuplesRead,
        Long indexScans,
        Double sequentialScanRatio) {}
