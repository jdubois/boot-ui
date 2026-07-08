package io.github.jdubois.bootui.autoconfigure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityOperation;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class CacheActivityCacheManagerTests {

    @Test
    void wrapsCachesAndCapturesHitsAndMisses() {
        CacheManager delegate = new ConcurrentMapCacheManager("orders");
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        CacheManager wrapped = new CacheActivityCacheManager(delegate, recorder, "cacheManager");

        Cache cache = wrapped.getCache("orders");
        assertThat(cache.get("42")).isNull();
        cache.put("42", "value");
        assertThat(cache.get("42").get()).isEqualTo("value");

        List<CacheActivityEvent> events = recorder.recentEvents();
        assertThat(events)
                .extracting(CacheActivityEvent::operation)
                .containsExactly(CacheActivityOperation.MISS, CacheActivityOperation.PUT, CacheActivityOperation.HIT);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.managerName()).isEqualTo("cacheManager");
            assertThat(event.cacheName()).isEqualTo("orders");
        });
    }

    @Test
    void capturesEvictAndClear() {
        CacheManager delegate = new ConcurrentMapCacheManager("orders");
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        Cache cache = new CacheActivityCacheManager(delegate, recorder, "cacheManager").getCache("orders");

        cache.put("42", "value");
        cache.evict("42");
        cache.clear();

        assertThat(recorder.recentEvents())
                .extracting(CacheActivityEvent::operation)
                .containsExactly(
                        CacheActivityOperation.PUT, CacheActivityOperation.EVICT, CacheActivityOperation.CLEAR);
    }

    @Test
    void getWithCallableRecordsMissWhenValueLoaderInvokedAndHitOtherwise() {
        CacheManager delegate = new ConcurrentMapCacheManager("orders");
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        Cache cache = new CacheActivityCacheManager(delegate, recorder, "cacheManager").getCache("orders");

        Callable<String> loader = () -> "loaded";
        assertThat(cache.get("42", loader)).isEqualTo("loaded");
        assertThat(cache.get("42", loader)).isEqualTo("loaded");

        assertThat(recorder.recentEvents())
                .extracting(CacheActivityEvent::operation)
                .containsExactly(CacheActivityOperation.MISS, CacheActivityOperation.HIT);
    }

    @Test
    void returnsNullWithoutRecordingWhenCacheDoesNotExist() {
        CacheManager delegate = mock(CacheManager.class);
        when(delegate.getCache("missing")).thenReturn(null);
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        CacheManager wrapped = new CacheActivityCacheManager(delegate, recorder, "cacheManager");

        assertThat(wrapped.getCache("missing")).isNull();
        assertThat(recorder.recentEvents()).isEmpty();
    }

    @Test
    void reusesTheSameWrappedCacheInstanceForRepeatedLookups() {
        CacheManager delegate = new ConcurrentMapCacheManager("orders");
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        CacheManager wrapped = new CacheActivityCacheManager(delegate, recorder, "cacheManager");

        assertThat(wrapped.getCache("orders")).isSameAs(wrapped.getCache("orders"));
    }

    @Test
    void delegatesGetCacheNames() {
        CacheManager delegate = new ConcurrentMapCacheManager("orders", "customers");
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        CacheManager wrapped = new CacheActivityCacheManager(delegate, recorder, "cacheManager");

        assertThat(wrapped.getCacheNames()).containsExactlyInAnyOrder("orders", "customers");
    }

    @Test
    void unwrapReturnsTheRealCacheManager() {
        CacheManager delegate = new ConcurrentMapCacheManager("orders");
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        CacheManager wrapped = new CacheActivityCacheManager(delegate, recorder, "cacheManager");

        assertThat(CacheActivityAware.unwrap(wrapped)).isSameAs(delegate);
        assertThat(CacheActivityAware.unwrap(delegate)).isSameAs(delegate);
    }
}
