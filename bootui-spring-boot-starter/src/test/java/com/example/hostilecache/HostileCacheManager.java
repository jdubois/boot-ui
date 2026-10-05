package com.example.hostilecache;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * A cache manager whose single cache throws from {@code getNativeCache()}, standing in for a broken or
 * half-present cache provider.
 *
 * <p>It lives outside the {@code io.github.jdubois.bootui} package on purpose: {@code BootUiSelfDataFilter}
 * hides BootUI's own beans, so a fixture in that package would never reach the inspectors at all.</p>
 */
public record HostileCacheManager(String cacheName, Throwable failure) implements CacheManager {

    @Override
    public Cache getCache(String name) {
        return cacheName.equals(name) ? new HostileCache(name, failure) : null;
    }

    @Override
    public Collection<String> getCacheNames() {
        return List.of(cacheName);
    }

    private record HostileCache(String name, Throwable failure) implements Cache {

        private Object fail() {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return fail();
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
