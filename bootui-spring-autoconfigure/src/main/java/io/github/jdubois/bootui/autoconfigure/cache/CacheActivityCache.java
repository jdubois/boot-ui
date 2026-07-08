package io.github.jdubois.bootui.autoconfigure.cache;

import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import java.util.concurrent.Callable;
import org.springframework.cache.Cache;

/**
 * Decorates a real {@link Cache} so every {@link #get}/{@link #put}/{@link #evict}/{@link #clear} call is
 * captured by a {@link CacheActivityRecorder} before being delegated unchanged — pass-through by default,
 * exactly as {@code SqlTracingProxies} wraps JDBC and {@code NotifyingHttpExchangeRepository} wraps HTTP
 * exchange storage.
 *
 * <p>Only the primary synchronous accessors are instrumented (see {@code docs/PLAN.md} §3.4's "lightweight,
 * sampled" scope): {@link #retrieve}, {@link #putIfAbsent}, {@link #evictIfPresent} and {@link #invalidate}
 * delegate straight through, uninstrumented.</p>
 */
final class CacheActivityCache implements Cache {

    private final Cache delegate;
    private final CacheActivityRecorder recorder;
    private final String managerName;

    CacheActivityCache(Cache delegate, CacheActivityRecorder recorder, String managerName) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.managerName = managerName;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper value = delegate.get(key);
        recordGet(key, value != null);
        return value;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        T value = delegate.get(key, type);
        recordGet(key, value != null);
        return value;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        boolean[] loaded = {false};
        T value = delegate.get(key, () -> {
            loaded[0] = true;
            return valueLoader.call();
        });
        recordGet(key, !loaded[0]);
        return value;
    }

    @Override
    public void put(Object key, Object value) {
        delegate.put(key, value);
        recorder.recordPut(managerName, getName(), key);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        return delegate.putIfAbsent(key, value);
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
        recorder.recordEvict(managerName, getName(), key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return delegate.evictIfPresent(key);
    }

    @Override
    public void clear() {
        delegate.clear();
        recorder.recordClear(managerName, getName());
    }

    @Override
    public boolean invalidate() {
        return delegate.invalidate();
    }

    private void recordGet(Object key, boolean hit) {
        if (hit) {
            recorder.recordHit(managerName, getName(), key);
        } else {
            recorder.recordMiss(managerName, getName(), key);
        }
    }
}
