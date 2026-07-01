package io.github.jdubois.bootui.quarkus.cache;

import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheOperationDiscovery;
import io.github.jdubois.bootui.spi.CacheProvider;
import io.github.jdubois.bootui.spi.CacheSnapshot;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CaffeineCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus {@link CacheProvider}: the cache-specific seam behind the shared engine {@code CacheService},
 * backed by the Quarkus {@link CacheManager}. It is the <strong>sole</strong> importer of the
 * {@code io.quarkus.cache.*} API in BootUI; the engine stays cache-API-free.
 *
 * <p>This class is constructed only by {@link io.github.jdubois.bootui.quarkus.BootUiCacheProducer}, which the
 * deployment processor excludes from bean discovery unless the {@code CACHE} capability is present (R2), so the
 * {@code io.quarkus.cache} types it references are never linked in a cache-absent application.</p>
 *
 * <p>Quarkus binds caches through build-time annotations ({@code @CacheResult}/{@code @CacheInvalidate}) on
 * methods, with no runtime registry of which methods carry them — so {@link #operations()} is intentionally
 * empty (reduced fidelity relative to Spring's {@code CacheOperationSource} discovery). The panel still shows
 * the live cache names, Caffeine sizes, Micrometer metrics and the clear action.</p>
 */
public class QuarkusCacheProvider implements CacheProvider {

    private static final String MANAGER_NAME = "cacheManager";

    private final CacheManager cacheManager;

    private final Config config;

    public QuarkusCacheProvider(CacheManager cacheManager, Config config) {
        this.cacheManager = cacheManager;
        this.config = config;
    }

    @Override
    public boolean available() {
        return cacheManager != null;
    }

    @Override
    public boolean clearEnabled() {
        return config.getOptionalValue("bootui.cache.clear-enabled", Boolean.class)
                .orElse(Boolean.TRUE);
    }

    @Override
    public List<CacheManagerSnapshot> managers() {
        if (cacheManager == null) {
            return List.of();
        }
        List<CacheSnapshot> caches = new ArrayList<>();
        for (String name : cacheManager.getCacheNames()) {
            Optional<Cache> cache = cacheManager.getCache(name);
            if (cache.isEmpty()) {
                continue;
            }
            caches.add(toSnapshot(name, cache.get()));
        }
        String type = cacheManager.getClass().getName();
        boolean noOp = type.toLowerCase().contains("noop");
        return List.of(new CacheManagerSnapshot(MANAGER_NAME, type, noOp, caches));
    }

    @Override
    public CacheOperationDiscovery operations() {
        return CacheOperationDiscovery.empty();
    }

    @Override
    public Optional<String> clearUnavailableReason() {
        if (cacheManager == null) {
            return Optional.of("No cache manager is available.");
        }
        return Optional.empty();
    }

    @Override
    public boolean evict(String managerName, String cacheName) {
        if (cacheManager == null) {
            throw new IllegalStateException("No cache manager is available.");
        }
        Optional<Cache> cache = cacheManager.getCache(cacheName);
        if (cache.isEmpty()) {
            return false;
        }
        cache.get().invalidateAll().await().indefinitely();
        return true;
    }

    private CacheSnapshot toSnapshot(String name, Cache cache) {
        String nativeType = cache.getClass().getName();
        Long size = null;
        if (cache instanceof CaffeineCache caffeine) {
            // The public CaffeineCache interface exposes only keySet() (which copies the key set), not the
            // O(1) estimatedSize() on the internal CaffeineCacheImpl. We accept the copy rather than couple
            // to the internal impl by reflection: the Quarkus console is prod-dark (wired only in dev/test),
            // where caches are small and this read happens only on an explicit panel GET.
            size = (long) caffeine.keySet().size();
        }
        return new CacheSnapshot(name, nativeType, size);
    }
}
