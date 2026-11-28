package io.github.jdubois.bootui.quarkus4.hibernate;

import io.github.jdubois.bootui.spi.HibernateCacheRegionSnapshot;
import io.github.jdubois.bootui.spi.HibernateStatisticsProvider;
import io.github.jdubois.bootui.spi.HibernateStatisticsSnapshot;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.CacheRegionStatistics;
import org.hibernate.stat.Statistics;

/**
 * Quarkus adapter {@link HibernateStatisticsProvider}: resolves the first
 * {@code @Any Instance<EntityManagerFactory>} CDI bean, unwraps it to {@code org.hibernate.SessionFactory},
 * and maps its live {@code org.hibernate.stat.Statistics} onto the framework-neutral
 * {@link HibernateStatisticsSnapshot}.
 *
 * <p>Only the first resolved {@code EntityManagerFactory} is inspected — multiple persistence units are a
 * known limitation of this iteration (see the Hibernate Statistics panel documentation). The
 * {@code SessionFactory} is resolved <em>live</em> on every call, mirroring the Spring adapter's
 * {@code SpringHibernateStatisticsProvider}.</p>
 */
public final class QuarkusHibernateStatisticsProvider implements HibernateStatisticsProvider {

    private final Instance<EntityManagerFactory> entityManagerFactories;

    public QuarkusHibernateStatisticsProvider(@Any Instance<EntityManagerFactory> entityManagerFactories) {
        this.entityManagerFactories = entityManagerFactories;
    }

    @Override
    public boolean available() {
        return sessionFactory() != null;
    }

    @Override
    public boolean statisticsEnabled() {
        SessionFactory sessionFactory = sessionFactory();
        return sessionFactory != null && sessionFactory.getStatistics().isStatisticsEnabled();
    }

    @Override
    public void enableStatistics() {
        SessionFactory sessionFactory = sessionFactory();
        if (sessionFactory == null) {
            throw new IllegalStateException("No Hibernate SessionFactory could be resolved.");
        }
        sessionFactory.getStatistics().setStatisticsEnabled(true);
    }

    @Override
    public HibernateStatisticsSnapshot snapshot() {
        SessionFactory sessionFactory = sessionFactory();
        return sessionFactory == null ? null : map(sessionFactory);
    }

    private SessionFactory sessionFactory() {
        if (entityManagerFactories.isUnsatisfied()) {
            return null;
        }
        EntityManagerFactory entityManagerFactory;
        try {
            entityManagerFactory = entityManagerFactories.iterator().next();
        } catch (RuntimeException ex) {
            return null;
        }
        try {
            return entityManagerFactory.unwrap(SessionFactory.class);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // Byte-identical mapping to SpringHibernateStatisticsProvider#map: duplicated (not shared) because it
    // reads org.hibernate.stat.Statistics, an optional Hibernate ORM type that must stay module-local (R2).
    private static HibernateStatisticsSnapshot map(SessionFactory sessionFactory) {
        Statistics statistics = sessionFactory.getStatistics();
        // Statistics counters stay at zero until a query/cache is actually used, so an activity-based
        // heuristic would misreport an enabled-but-unused cache as disabled. SessionFactoryOptions carries
        // the authoritative configured flag instead; fall back to the heuristic only if the SPI type isn't
        // available (e.g. a non-standard SessionFactory implementation).
        boolean queryCacheEnabled;
        boolean secondLevelCacheEnabled;
        SessionFactoryOptions options = sessionFactoryOptions(sessionFactory);
        if (options != null) {
            queryCacheEnabled = options.isQueryCacheEnabled();
            secondLevelCacheEnabled = options.isSecondLevelCacheEnabled();
        } else {
            queryCacheEnabled = statistics.getQueryCacheHitCount() > 0
                    || statistics.getQueryCacheMissCount() > 0
                    || statistics.getQueryCachePutCount() > 0;
            secondLevelCacheEnabled = statistics.getSecondLevelCacheRegionNames().length > 0;
        }
        // getSecondLevelCacheRegionNames() returns both entity/collection ("domain data") regions and the
        // query-result-cache region (e.g. "default-query-results-region"); getDomainDataRegionStatistics
        // throws IllegalArgumentException for the latter, so skip regions that aren't domain data — the
        // query cache's own hit/miss/put counters are already reported above via queryCacheHitCount etc.
        String[] regionNames = statistics.getSecondLevelCacheRegionNames();
        List<HibernateCacheRegionSnapshot> regions = new ArrayList<>(regionNames.length);
        for (String regionName : regionNames) {
            CacheRegionStatistics regionStatistics;
            try {
                regionStatistics = statistics.getDomainDataRegionStatistics(regionName);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            regions.add(new HibernateCacheRegionSnapshot(
                    regionName,
                    regionStatistics.getHitCount(),
                    regionStatistics.getMissCount(),
                    regionStatistics.getPutCount()));
        }
        return new HibernateStatisticsSnapshot(
                statistics.getSessionOpenCount(),
                statistics.getSessionCloseCount(),
                statistics.getFlushCount(),
                statistics.getConnectCount(),
                statistics.getTransactionCount(),
                statistics.getSuccessfulTransactionCount(),
                statistics.getEntityLoadCount(),
                statistics.getEntityFetchCount(),
                statistics.getEntityInsertCount(),
                statistics.getEntityUpdateCount(),
                statistics.getEntityDeleteCount(),
                statistics.getCollectionLoadCount(),
                statistics.getCollectionFetchCount(),
                statistics.getCollectionRecreateCount(),
                statistics.getCollectionUpdateCount(),
                statistics.getCollectionRemoveCount(),
                statistics.getQueryExecutionCount(),
                statistics.getQueryExecutionMaxTime(),
                statistics.getQueryExecutionMaxTimeQueryString(),
                queryCacheEnabled,
                statistics.getQueryCacheHitCount(),
                statistics.getQueryCacheMissCount(),
                statistics.getQueryCachePutCount(),
                secondLevelCacheEnabled,
                statistics.getSecondLevelCacheHitCount(),
                statistics.getSecondLevelCacheMissCount(),
                statistics.getSecondLevelCachePutCount(),
                regions);
    }

    private static SessionFactoryOptions sessionFactoryOptions(SessionFactory sessionFactory) {
        try {
            return sessionFactory.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
