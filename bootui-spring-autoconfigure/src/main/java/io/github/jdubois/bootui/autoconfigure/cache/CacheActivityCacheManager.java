package io.github.jdubois.bootui.autoconfigure.cache;

import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Decorates a real {@link CacheManager} so every {@link Cache} it hands out is wrapped in a
 * {@link CacheActivityCache}, capturing hit/miss/put/evict/clear regardless of whether the access came
 * from a {@code @Cacheable}/{@code @CachePut}/{@code @CacheEvict}-annotated method (Spring's caching AOP
 * resolves caches via {@link #getCache}, exactly like any programmatic caller) or direct
 * {@code CacheManager} use.
 */
final class CacheActivityCacheManager implements CacheManager, CacheActivityAware {

    private final CacheManager delegate;
    private final CacheActivityRecorder recorder;
    private final String managerName;
    private final Map<String, Cache> wrapped = new ConcurrentHashMap<>();

    CacheActivityCacheManager(CacheManager delegate, CacheActivityRecorder recorder, String managerName) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.managerName = managerName;
    }

    @Override
    public Cache getCache(String name) {
        Cache real = delegate.getCache(name);
        if (real == null) {
            return null;
        }
        return wrapped.computeIfAbsent(name, ignored -> new CacheActivityCache(real, recorder, managerName));
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    @Override
    public CacheManager getTargetCacheManager() {
        return delegate;
    }
}
