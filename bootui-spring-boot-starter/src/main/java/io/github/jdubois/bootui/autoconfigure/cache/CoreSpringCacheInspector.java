package io.github.jdubois.bootui.autoconfigure.cache;

import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import io.github.jdubois.bootui.spi.CacheTierSnapshot;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.AbstractCacheManager;
import org.springframework.cache.support.CompositeCacheManager;
import org.springframework.cache.support.NoOpCache;
import org.springframework.cache.support.NoOpCacheManager;

/**
 * The always-available inspector, backed only by Spring Framework's own cache API and the JDK.
 *
 * <p>It recognises the plain in-memory caches whose native store is a {@code java.util} map or collection (the
 * {@code spring.cache.type=simple} default), and classifies the cache managers Spring Framework itself ships.
 * Everything else is left to a gated inspector or reported opaque.</p>
 */
final class CoreSpringCacheInspector implements SpringCacheInspector, SpringCacheManagerInspector {

    @Override
    public Optional<Inspection> inspect(Cache cache, Object nativeCache) {
        if (cache instanceof NoOpCache) {
            CacheStatisticsSnapshot statistics = CacheStatisticsSnapshot.unavailable(
                    "Spring Framework",
                    "A no-op cache stores nothing and records nothing; every lookup is a miss by design.");
            CacheTierSnapshot tier = new CacheTierSnapshot(
                    "no-op",
                    "No-op",
                    0,
                    cache.getClass().getName(),
                    CacheTierSnapshot.LOCALITY_LOCAL,
                    0L,
                    null,
                    "A no-op cache holds no entries at all, so its maximum size is zero by design.",
                    statistics);
            return Optional.of(new Inspection(List.of(tier), statistics, 0L));
        }
        Long size = jdkSize(nativeCache);
        if (size == null) {
            return Optional.empty();
        }
        CacheStatisticsSnapshot statistics = CacheStatisticsSnapshot.unavailable(
                "java.util", "A plain in-memory map does not record cache hit, miss or eviction counters.");
        CacheTierSnapshot tier = new CacheTierSnapshot(
                "map",
                "In-memory map",
                0,
                nativeCache.getClass().getName(),
                CacheTierSnapshot.LOCALITY_LOCAL,
                null,
                null,
                null,
                statistics);
        return Optional.of(new Inspection(List.of(tier), statistics, size));
    }

    @Override
    public Optional<Structure> inspect(CacheManager manager) {
        if (manager instanceof CompositeCacheManager) {
            // CompositeCacheManager consults its delegates in order but exposes no getter for them, so the
            // delegate list stays empty rather than being read out of a private field by reflection.
            return Optional.of(new Structure(
                    CacheManagerSnapshot.COMPOSITION_COMPOSITE, CacheManagerSnapshot.DYNAMIC_UNKNOWN, List.of()));
        }
        if (manager instanceof NoOpCacheManager) {
            // Documented public behaviour: a NoOpCacheManager answers every cache name with a no-op cache.
            return Optional.of(new Structure(
                    CacheManagerSnapshot.COMPOSITION_SIMPLE, CacheManagerSnapshot.DYNAMIC_YES, List.of()));
        }
        if (manager instanceof ConcurrentMapCacheManager || manager instanceof AbstractCacheManager) {
            // These managers own their caches directly. Whether they also create caches on demand depends on
            // configuration that neither exposes, so the dynamic state stays unknown.
            return Optional.of(new Structure(
                    CacheManagerSnapshot.COMPOSITION_SIMPLE, CacheManagerSnapshot.DYNAMIC_UNKNOWN, List.of()));
        }
        return Optional.empty();
    }

    private Long jdkSize(Object nativeCache) {
        if (!isJdkLocalType(nativeCache)) {
            return null;
        }
        if (nativeCache instanceof Map<?, ?> map) {
            return (long) map.size();
        }
        if (nativeCache instanceof Collection<?> collection) {
            return (long) collection.size();
        }
        return null;
    }

    private boolean isJdkLocalType(Object value) {
        String name = value.getClass().getName();
        return name.startsWith("java.util.") || name.startsWith("java.util.concurrent.");
    }
}
