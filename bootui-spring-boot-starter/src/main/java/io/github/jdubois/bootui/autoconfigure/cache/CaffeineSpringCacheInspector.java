package io.github.jdubois.bootui.autoconfigure.cache;

import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.github.jdubois.bootui.engine.support.CacheExpiryText;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import io.github.jdubois.bootui.spi.CacheTierSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.Cache;

/**
 * Caffeine inspector, instantiated by {@link SpringCacheInspectors} only when Caffeine is on the classpath.
 *
 * <p>Everything it reads is public Caffeine API: {@code Cache#policy()} for the configured maximum size and
 * expiry, {@code Policy#isRecordingStats()} to tell a cache that is not recording from one that has recorded
 * nothing yet, {@code Cache#stats()} for the counters and {@code Cache#estimatedSize()} for the entry count.
 * Reading them creates no entry and resets no counter.</p>
 */
final class CaffeineSpringCacheInspector implements SpringCacheInspector {

    private static final String PROVIDER = "Caffeine";

    @Override
    public Optional<Inspection> inspect(Cache cache, Object nativeCache) {
        if (!(nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine)) {
            return Optional.empty();
        }
        Policy<?, ?> policy = caffeine.policy();
        CacheStatisticsSnapshot statistics = statistics(caffeine, policy);
        CacheTierSnapshot tier = new CacheTierSnapshot(
                "caffeine",
                "Caffeine",
                0,
                nativeCache.getClass().getName(),
                CacheTierSnapshot.LOCALITY_LOCAL,
                maximumSize(policy),
                expiryPolicy(policy),
                policyNote(policy),
                statistics);
        return Optional.of(new Inspection(List.of(tier), statistics, caffeine.estimatedSize()));
    }

    private CacheStatisticsSnapshot statistics(
            com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine, Policy<?, ?> policy) {
        if (!policy.isRecordingStats()) {
            return CacheStatisticsSnapshot.unavailable(
                    PROVIDER,
                    "This Caffeine cache was not built with recordStats(), so it records no hit, miss or"
                            + " eviction counters.");
        }
        CacheStats stats = caffeine.stats();
        // Caffeine's CacheStats exposes no put or explicit-removal counter, so both stay unset (unknown)
        // rather than being reported as zero.
        return CacheStatisticsSnapshot.recording(PROVIDER, CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME)
                .requests(stats.requestCount())
                .hits(stats.hitCount())
                .misses(stats.missCount())
                .evictions(stats.evictionCount())
                .loadSuccesses(stats.loadSuccessCount())
                .loadFailures(stats.loadFailureCount())
                .size(caffeine.estimatedSize())
                .countersComparable()
                .build();
    }

    private Long maximumSize(Policy<?, ?> policy) {
        return policy.eviction()
                // A weighted cache's maximum is a total weight, not an entry count, so it is not reported as
                // a maximum size.
                .filter(eviction -> !eviction.isWeighted())
                .map(Policy.Eviction::getMaximum)
                .orElse(null);
    }

    private String expiryPolicy(Policy<?, ?> policy) {
        return CacheExpiryText.summary(
                policy.expireAfterWrite()
                        .map(expiration -> CacheExpiryText.expireAfterWrite(expiration.getExpiresAfter()))
                        .orElse(null),
                policy.expireAfterAccess()
                        .map(expiration -> CacheExpiryText.expireAfterAccess(expiration.getExpiresAfter()))
                        .orElse(null),
                policy.refreshAfterWrite()
                        .map(refresh -> CacheExpiryText.refreshAfterWrite(refresh.getRefreshesAfter()))
                        .orElse(null),
                policy.expireVariably().isPresent() ? CacheExpiryText.expirePerEntry() : null);
    }

    /**
     * A weighted Caffeine cache is bounded, but by total weight rather than entry count, so no maximum size is
     * reported for it. Saying that explicitly keeps it from reading as an unbounded cache.
     */
    private String policyNote(Policy<?, ?> policy) {
        boolean weighted = policy.eviction().map(Policy.Eviction::isWeighted).orElse(false);
        return weighted
                ? "This cache is bounded by a maximum total weight, not by a maximum entry count, so no"
                        + " maximum size is reported."
                : null;
    }
}
