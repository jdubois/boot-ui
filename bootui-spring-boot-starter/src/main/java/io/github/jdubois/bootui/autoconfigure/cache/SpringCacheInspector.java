package io.github.jdubois.bootui.autoconfigure.cache;

import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import io.github.jdubois.bootui.spi.CacheTierSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.Cache;

/**
 * Describes the storage behind one Spring {@link Cache} using only that implementation's public, supported
 * API: its backing tiers, their configured limits and its native statistics.
 *
 * <p>Implementations that reference an optional library (Caffeine, Spring Data Redis) are instantiated by
 * {@link SpringCacheInspectors} only after a classpath check, so the optional types are never linked in an
 * application that does not use them. An inspector that does not recognise a cache returns
 * {@link Optional#empty()} and the cache is reported opaque — BootUI never invents a tier from a class name and
 * never reflects into a provider's internals.</p>
 */
interface SpringCacheInspector {

    /**
     * @param cache the Spring cache, already unwrapped from BootUI's activity-capture decorator
     * @param nativeCache the value of {@link Cache#getNativeCache()}, never {@code null}
     * @return the discovered storage description, or empty when this inspector does not recognise the cache
     */
    Optional<Inspection> inspect(Cache cache, Object nativeCache);

    /**
     * One inspector's findings for a cache.
     *
     * @param tiers the discovered tiers, ordered from the tier consulted first; never empty
     * @param statistics whole-cache native statistics
     * @param size an estimated entry count read without a network call, or {@code null}
     */
    record Inspection(List<CacheTierSnapshot> tiers, CacheStatisticsSnapshot statistics, Long size) {}
}
