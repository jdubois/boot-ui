package io.github.jdubois.bootui.core.dto;

/**
 * One index and how often this node has actually used it.
 *
 * <p>{@link #scans()} counts scans on the server that answered the query and resets with the statistics. A
 * primary that never reads an index can still be replicating it to a standby that does, so a zero here is a
 * prompt to check the replicas, not a verdict.</p>
 */
public record PostgresIndexDto(
        String schema,
        String table,
        String index,
        Long scans,
        Long tuplesRead,
        Long sizeBytes,
        boolean unique,
        boolean primaryKey,
        boolean constraintBacked) {}
