package io.github.jdubois.bootui.engine.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheActivityRecorderTests {

    @Test
    void recordsNothingWhenDisabled() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(false, 10);
        recorder.recordHit("cacheManager", "orders", "42");
        assertThat(recorder.recentEvents()).isEmpty();
    }

    @Test
    void recordsHitMissPutEvictAndClear() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.recordHit("cacheManager", "orders", "42");
        recorder.recordMiss("cacheManager", "orders", "43");
        recorder.recordPut("cacheManager", "orders", "44");
        recorder.recordEvict("cacheManager", "orders", "45");
        recorder.recordClear("cacheManager", "orders");

        List<CacheActivityEvent> events = recorder.recentEvents();
        assertThat(events)
                .extracting(CacheActivityEvent::operation)
                .containsExactly(
                        CacheActivityOperation.HIT,
                        CacheActivityOperation.MISS,
                        CacheActivityOperation.PUT,
                        CacheActivityOperation.EVICT,
                        CacheActivityOperation.CLEAR);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.managerName()).isEqualTo("cacheManager");
            assertThat(event.cacheName()).isEqualTo("orders");
        });
    }

    @Test
    void neverRecordsRawKeysOnlyAStableHash() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.recordHit("cacheManager", "orders", "super-secret-key");

        CacheActivityEvent event = recorder.recentEvents().get(0);
        assertThat(event.keyHash()).isNotNull().doesNotContain("super-secret-key");
        assertThat(event.keyHash()).isEqualTo(CacheActivityRecorder.hashKey("super-secret-key"));
    }

    @Test
    void clearHasNoKeyHash() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.recordClear("cacheManager", "orders");
        assertThat(recorder.recentEvents().get(0).keyHash()).isNull();
    }

    @Test
    void hashKeyIsStableAndDeterministic() {
        assertThat(CacheActivityRecorder.hashKey("42")).isEqualTo(CacheActivityRecorder.hashKey("42"));
        assertThat(CacheActivityRecorder.hashKey("42")).isNotEqualTo(CacheActivityRecorder.hashKey("43"));
        assertThat(CacheActivityRecorder.hashKey("42")).hasSize(16);
    }

    @Test
    void evictsOldestEventsBeyondCapacity() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 2);
        recorder.recordHit("cacheManager", "orders", "1");
        recorder.recordHit("cacheManager", "orders", "2");
        recorder.recordHit("cacheManager", "orders", "3");

        assertThat(recorder.recentEvents()).hasSize(2);
        assertThat(recorder.recentEvents()).extracting(CacheActivityEvent::seq).containsExactly(2L, 3L);
    }

    @Test
    void clearEmptiesTheBuffer() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.recordHit("cacheManager", "orders", "1");
        recorder.clear();
        assertThat(recorder.recentEvents()).isEmpty();
    }

    @Test
    void notifiesSubscribersOnEachRecordedEvent() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        AtomicInteger notifications = new AtomicInteger();
        Runnable unsubscribe = recorder.subscribe(notifications::incrementAndGet);

        recorder.recordHit("cacheManager", "orders", "1");
        assertThat(notifications.get()).isEqualTo(1);

        unsubscribe.run();
        recorder.recordHit("cacheManager", "orders", "2");
        assertThat(notifications.get()).isEqualTo(1);
    }

    @Test
    void stampsTraceIdFromConfiguredProvider() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.setTraceIdProvider(() -> "trace-x");
        recorder.recordHit("cacheManager", "orders", "1");
        assertThat(recorder.recentEvents().get(0).traceId()).isEqualTo("trace-x");
    }

    @Test
    void usesNoTraceIdByDefault() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.recordHit("cacheManager", "orders", "1");
        assertThat(recorder.recentEvents().get(0).traceId()).isNull();
    }

    @Test
    void treatsBlankProviderTraceIdAsNone() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.setTraceIdProvider(() -> "   ");
        recorder.recordHit("cacheManager", "orders", "1");
        assertThat(recorder.recentEvents().get(0).traceId()).isNull();
    }

    @Test
    void guardsAgainstThrowingProvider() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.setTraceIdProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        recorder.recordHit("cacheManager", "orders", "1");
        assertThat(recorder.recentEvents()).hasSize(1);
        assertThat(recorder.recentEvents().get(0).traceId()).isNull();
    }

    @Test
    void nullProviderRestoresDefault() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.setTraceIdProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        recorder.setTraceIdProvider(null);
        recorder.recordHit("cacheManager", "orders", "1");
        assertThat(recorder.recentEvents().get(0).traceId()).isNull();
    }

    @Test
    void isEnabledReflectsConstructorFlag() {
        assertThat(new CacheActivityRecorder(true, 10).isEnabled()).isTrue();
        assertThat(new CacheActivityRecorder(false, 10).isEnabled()).isFalse();
    }
}
