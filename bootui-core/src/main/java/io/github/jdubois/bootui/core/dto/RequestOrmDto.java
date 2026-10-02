package io.github.jdubois.bootui.core.dto;

/**
 * The Hibernate work of one request ({@code docs/PLAN-v2.md} §5.18, M4-9), summed over the sessions it opened, from the
 * runtime journal's {@code orm} source. Flush times are Hibernate's own, without the statements a flush executed.
 *
 * @param sessions the Hibernate sessions that did database work
 * @param statements the JDBC statements and batches they executed
 * @param statementMicros the time those statements took
 * @param connectionAcquisitions the JDBC connections they obtained
 * @param flushes the full flushes, at commit or explicit
 * @param autoFlushes the auto-flushes that wrote pending changes before a query
 * @param flushMicros Hibernate's own time in full flushes
 * @param autoFlushMicros Hibernate's own time in auto-flushes, including checks that wrote nothing
 * @param dirtyEntities the entities a dirty check found modified
 * @param entitiesInContext the most entities one persistence context held at a flush, or {@code -1} when none flushed
 * @param l2Hits second-level cache hits
 * @param l2Misses second-level cache misses
 * @param l2Puts second-level cache puts
 */
public record RequestOrmDto(
        int sessions,
        int statements,
        long statementMicros,
        int connectionAcquisitions,
        int flushes,
        int autoFlushes,
        long flushMicros,
        long autoFlushMicros,
        int dirtyEntities,
        int entitiesInContext,
        int l2Hits,
        int l2Misses,
        int l2Puts) {}
