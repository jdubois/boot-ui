package io.github.jdubois.bootui.autoconfigure.cache;

import io.github.jdubois.bootui.engine.cache.CacheActivityOperation;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.spi.InvocationContextProvider;
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

    /** The real {@link Cache} this instance decorates. */
    Cache getTargetCache() {
        return delegate;
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
        InvocationContextProvider.Context context = recorder.captureContext();
        ValueWrapper value = delegate.get(key);
        recordGet(key, value != null, context);
        return value;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        InvocationContextProvider.Context context = recorder.captureContext();
        T value = delegate.get(key, type);
        recordGet(key, value != null, context);
        return value;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        InvocationContextProvider.Context context = recorder.captureContext();
        boolean[] loaded = {false};
        T value = delegate.get(key, () -> {
            loaded[0] = true;
            return valueLoader.call();
        });
        recordGet(key, !loaded[0], context);
        return value;
    }

    @Override
    public void put(Object key, Object value) {
        InvocationContextProvider.Context context = recorder.captureContext();
        delegate.put(key, value);
        recorder.record(managerName, getName(), CacheActivityOperation.PUT, key, context);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        return delegate.putIfAbsent(key, value);
    }

    @Override
    public void evict(Object key) {
        InvocationContextProvider.Context context = recorder.captureContext();
        delegate.evict(key);
        recorder.record(managerName, getName(), CacheActivityOperation.EVICT, key, context);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return delegate.evictIfPresent(key);
    }

    @Override
    public void clear() {
        InvocationContextProvider.Context context = recorder.captureContext();
        delegate.clear();
        recorder.record(managerName, getName(), CacheActivityOperation.CLEAR, null, context);
    }

    @Override
    public boolean invalidate() {
        return delegate.invalidate();
    }

    private void recordGet(Object key, boolean hit, InvocationContextProvider.Context context) {
        recorder.record(
                managerName, getName(), hit ? CacheActivityOperation.HIT : CacheActivityOperation.MISS, key, context);
    }
}
