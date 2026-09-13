package io.github.jdubois.bootui.core.dto;

/**
 * One relation's autovacuum health.
 *
 * <p>{@link #vacuumDue()} is computed against the settings the server would actually use for this relation:
 * its own {@code reloptions} where it sets them, the cluster settings otherwise, and never assumed defaults —
 * so neither a tuned server nor an individually tuned table is reported against numbers it never used.</p>
 *
 * @param vacuumThreshold {@code threshold + scale_factor * liveTuples}, the dead-tuple count at which
 *     autovacuum would trigger for this relation, using this relation's effective settings
 * @param autovacuumEnabled whether autovacuum is enabled for this relation: the cluster setting, unless the
 *     table's own {@code reloptions} turn it off
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
