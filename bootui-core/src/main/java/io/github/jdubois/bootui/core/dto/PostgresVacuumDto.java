package io.github.jdubois.bootui.core.dto;

/**
 * One relation's autovacuum health.
 *
 * <p>{@link #vacuumDue()} is computed against the server's <em>actual</em>
 * {@code autovacuum_vacuum_threshold} and {@code autovacuum_vacuum_scale_factor} settings rather than assumed
 * defaults, so a tuned server is not reported against numbers it never used.</p>
 *
 * @param vacuumThreshold {@code threshold + scale_factor * liveTuples}, the dead-tuple count at which
 *     autovacuum would trigger for this relation
 */
public record PostgresVacuumDto(
        String schema,
        String table,
        Long liveTuples,
        Long deadTuples,
        Double deadTupleRatio,
        Long vacuumThreshold,
        boolean vacuumDue,
        boolean autovacuumEnabled,
        Long lastVacuum,
        Long lastAutoVacuum,
        Long lastAnalyze,
        Long lastAutoAnalyze) {}
