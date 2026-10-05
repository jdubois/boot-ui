package com.example.sealedcache;

import java.util.Set;
import java.util.concurrent.Callable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * A cache manager whose caches expose a native store BootUI has no public API to describe, used to prove the
 * Cache panel reports such a cache as opaque instead of guessing at its tiers.
 *
 * <p>It deliberately lives outside the {@code io.github.jdubois.bootui} package, because BootUI's own
 * self-data filter hides cache managers declared inside it.</p>
 */
public final class SealedCacheManager implements CacheManager {

    private final Set<String> names;

    public SealedCacheManager(String... names) {
        this.names = Set.of(names);
    }

    @Override
    public Cache getCache(String name) {
        return names.contains(name) ? new SealedCache(name) : null;
    }

    @Override
    public Set<String> getCacheNames() {
        return names;
    }

    private static final class SealedCache implements Cache {

        private final String name;

        private SealedCache(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return new Object();
        }

        @Override
        public ValueWrapper get(Object key) {
            return null;
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return null;
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            return null;
        }

        @Override
        public void put(Object key, Object value) {}

        @Override
        public void evict(Object key) {}

        @Override
        public void clear() {}
    }
}
