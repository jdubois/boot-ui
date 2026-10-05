package io.github.jdubois.bootui.autoconfigure.cache;

import io.github.jdubois.bootui.engine.support.CacheExpiryText;
import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import io.github.jdubois.bootui.spi.CacheTierSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.FixedDurationTtlFunction;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;

/**
 * Spring Data Redis inspector, instantiated by {@link SpringCacheInspectors} only when Spring Data Redis is on
 * the classpath.
 *
 * <p>Everything it reads is public Spring Data Redis API and stays inside this JVM: the cache's own
 * {@code RedisCacheConfiguration} for the configured time-to-live, {@code RedisCache#getStatistics()} for the
 * locally recorded counters and {@code RedisCacheManager#isAllowRuntimeCacheCreation()} for the dynamic-cache
 * state. No entry count is reported, because counting Redis keys would mean an unsolicited network round trip
 * on panel render.</p>
 */
final class RedisSpringCacheInspector implements SpringCacheInspector, SpringCacheManagerInspector {

    private static final String PROVIDER = "Spring Data Redis";

    @Override
    public Optional<Inspection> inspect(Cache cache, Object nativeCache) {
        if (!(cache instanceof RedisCache redisCache)) {
            return Optional.empty();
        }
        CacheStatisticsSnapshot statistics = statistics(redisCache);
        CacheTierSnapshot tier = new CacheTierSnapshot(
                "redis",
                "Redis",
                0,
                // The native cache of a RedisCache is its RedisCacheWriter, which describes the connection
                // rather than the storage, so the tier names the RedisCache implementation itself.
                redisCache.getClass().getName(),
                CacheTierSnapshot.LOCALITY_DISTRIBUTED,
                null,
                expiryPolicy(redisCache.getCacheConfiguration()),
                null,
                statistics);
        return Optional.of(new Inspection(List.of(tier), statistics, null));
    }

    @Override
    public Optional<Structure> inspect(CacheManager manager) {
        if (manager instanceof RedisCacheManager redisCacheManager) {
            return Optional.of(new Structure(
                    CacheManagerSnapshot.COMPOSITION_SIMPLE,
                    redisCacheManager.isAllowRuntimeCacheCreation()
                            ? CacheManagerSnapshot.DYNAMIC_YES
                            : CacheManagerSnapshot.DYNAMIC_NO,
                    List.of()));
        }
        return Optional.empty();
    }

    private CacheStatisticsSnapshot statistics(RedisCache redisCache) {
        CacheStatistics statistics;
        try {
            statistics = redisCache.getStatistics();
        } catch (RuntimeException ex) {
            return CacheStatisticsSnapshot.unavailable(
                    PROVIDER,
                    "Redis cache statistics could not be read (" + ex.getClass().getSimpleName() + ").");
        }
        if (statistics == null || !isCollecting(statistics)) {
            return CacheStatisticsSnapshot.unavailable(
                    PROVIDER,
                    "Statistics collection is disabled on this RedisCacheManager; build it with"
                            + " enableStatistics() to collect counters.");
        }
        // Redis expires entries server-side without telling the client, so there is no eviction counter to
        // report; deletions requested by the application are counted as removals. No entry count is set
        // either, because counting Redis keys would require a network round trip the panel must never make.
        return CacheStatisticsSnapshot.recording(PROVIDER, CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME)
                .since(since(statistics))
                .requests(statistics.getGets())
                .hits(statistics.getHits())
                .misses(statistics.getMisses())
                .puts(statistics.getPuts())
                .removals(statistics.getDeletes())
                .countersComparable()
                .build();
    }

    /**
     * Spring Data Redis answers {@code getStatistics()} even when collection is disabled, with a no-op
     * implementation whose counters are all zero and whose start instant is the epoch. Treating that epoch
     * marker as "not collecting" keeps a disabled collector from rendering as a genuine all-zero series.
     *
     * <p>Note that the enabled collector's {@code getCacheStatistics(name)} lazily creates the per-cache entry
     * it returns, stamping it with the current instant. That is the collector's own bookkeeping — it records
     * no request and changes no counter — but it does mean the first read of a statistics-enabled cache that
     * has seen no traffic yet reports a start instant of "now"; see {@link #since(CacheStatistics)}.</p>
     */
    private boolean isCollecting(CacheStatistics statistics) {
        Instant since = statistics.getSince();
        return since != null && since.isAfter(Instant.EPOCH);
    }

    /**
     * The instant the reported counters started accumulating from, or {@code null} when nothing has been
     * counted yet. An untouched cache's entry is created on first read, so its start instant would be the
     * moment the panel was rendered rather than anything about the cache; reporting no instant at all is more
     * honest than a timestamp that means "you just looked at this".
     */
    private String since(CacheStatistics statistics) {
        if (isEmpty(statistics)) {
            return null;
        }
        Instant lastReset = statistics.getLastReset();
        Instant since = lastReset != null && lastReset.isAfter(Instant.EPOCH) ? lastReset : statistics.getSince();
        return since == null ? null : since.toString();
    }

    private boolean isEmpty(CacheStatistics statistics) {
        return statistics.getGets() == 0
                && statistics.getHits() == 0
                && statistics.getMisses() == 0
                && statistics.getPuts() == 0
                && statistics.getDeletes() == 0
                && statistics.getPending() == 0;
    }

    private String expiryPolicy(RedisCacheConfiguration configuration) {
        if (configuration == null) {
            return null;
        }
        boolean idle = configuration.isTimeToIdleEnabled();
        if (configuration.getTtlFunction() instanceof FixedDurationTtlFunction fixed) {
            return CacheExpiryText.timeToLive(fixed.duration(), idle);
        }
        return CacheExpiryText.timeToLiveComputedPerEntry(idle);
    }
}
