package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A live snapshot of one Hibernate {@code SessionFactory}'s runtime statistics (session/transaction
 * activity, entity and collection persistence counts, query execution, and query/second-level cache hit
 * ratios), read from {@code org.hibernate.stat.Statistics} when {@code hibernate.generate_statistics} is
 * enabled.
 *
 * <p>The statistics payload is read-only and carries no reset/clear action. The panel may explicitly enable
 * collection for the current runtime before reading it. Single persistence-unit applications are the
 * supported shape (see the panel documentation for the multi-persistence-unit limitation).</p>
 */
public record HibernateStatisticsDto(
        long sessionOpenCount,
        long sessionCloseCount,
        long flushCount,
        long connectCount,
        long transactionCount,
        long successfulTransactionCount,
        long entityLoadCount,
        long entityFetchCount,
        long entityInsertCount,
        long entityUpdateCount,
        long entityDeleteCount,
        long collectionLoadCount,
        long collectionFetchCount,
        long collectionRecreateCount,
        long collectionUpdateCount,
        long collectionRemoveCount,
        long queryExecutionCount,
        long queryExecutionMaxTime,
        String queryExecutionMaxTimeQueryString,
        boolean queryCacheEnabled,
        long queryCacheHitCount,
        long queryCacheMissCount,
        long queryCachePutCount,
        boolean secondLevelCacheEnabled,
        long secondLevelCacheHitCount,
        long secondLevelCacheMissCount,
        long secondLevelCachePutCount,
        List<HibernateCacheRegionStatisticsDto> secondLevelCacheRegions) {

    public HibernateStatisticsDto {
        secondLevelCacheRegions = DtoCollections.immutableCopy(secondLevelCacheRegions);
    }
}
