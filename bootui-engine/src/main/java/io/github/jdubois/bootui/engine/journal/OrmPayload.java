package io.github.jdubois.bootui.engine.journal;

/**
 * One Hibernate session's work ({@code docs/PLAN-v2.md} §5.18, M4-9), published when the session ends: its JDBC
 * statements and their time, connection acquisitions, full flushes and the auto-flushes that wrote before a query, the
 * entities it dirty-checked as dirty, the most entities its persistence context held at a flush, and second-level cache
 * use. Flush times are Hibernate's own, without the statements a flush executed, so they sit beside SQL time rather than
 * inside it. Never an entity, a parameter, or a statement.
 *
 * @param persistenceUnit the persistence unit, or {@code null} when Hibernate does not name it to the listener
 * @param partialFlushes the auto-flushes before a query that executed at least one statement
 * @param entitiesInContext the most entities the persistence context held at one of its flushes, or {@code -1} when it
 *     never flushed, as a read-only session does
 */
public record OrmPayload(
        String persistenceUnit,
        int statements,
        long statementNanos,
        int connectionAcquisitions,
        long acquisitionNanos,
        int flushes,
        long flushNanos,
        int partialFlushes,
        long partialFlushNanos,
        int dirtyEntities,
        int entitiesInContext,
        int l2Hits,
        int l2Misses,
        int l2Puts)
        implements RuntimeEventPayload {

    /** Hibernate's own time flushing, full and partial, without the statements the flushes executed. */
    public long hibernateNanos() {
        return Math.max(0, flushNanos) + Math.max(0, partialFlushNanos);
    }

    /** This session with its persistence unit replaced by the run's shared copy. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new OrmPayload(
                dictionary.shared(persistenceUnit),
                statements,
                statementNanos,
                connectionAcquisitions,
                acquisitionNanos,
                flushes,
                flushNanos,
                partialFlushes,
                partialFlushNanos,
                dirtyEntities,
                entitiesInContext,
                l2Hits,
                l2Misses,
                l2Puts);
    }

    /** Its fixed part and its string, counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with the persistence unit counted as a reference when {@code dictionary} shares it. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 88 + JournalDictionary.retained(dictionary, persistenceUnit);
    }
}
