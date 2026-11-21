package io.github.jdubois.bootui.engine.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.insights.CacheCapture;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CacheActivityRecorderTests {

    @Test
    void captureWindowsArePerManagerResetAcrossGapsAndNeverReadAClockOnProjection() {
        AtomicLong time = new AtomicLong(100);
        AtomicInteger clockCalls = new AtomicInteger();
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10, () -> {
            clockCalls.incrementAndGet();
            return time.get();
        });
        recorder.setRuntimeEventSink(event -> true);
        recorder.markInstrumentedManager("first");
        time.set(200);
        recorder.markInstrumentedManager("second");
        var firstWindow = recorder.captureState();
        assertThat(firstWindow.continuousSince()).containsEntry("first", 100L).containsEntry("second", 200L);
        int reads = clockCalls.get();
        assertThat(recorder.captureState()).isEqualTo(firstWindow);
        assertThat(clockCalls.get()).isEqualTo(reads);
        recorder.suspendForLowFootprint();
        assertThat(recorder.captureState().paused()).isTrue();
        time.set(400);
        recorder.resumeFromLowFootprint();
        var resumed = recorder.captureState();
        assertThat(resumed.epoch()).isGreaterThan(firstWindow.epoch());
        assertThat(resumed.continuousSince()).containsEntry("first", 400L).containsEntry("second", 400L);
        time.set(500);
        recorder.setRuntimeEventSink(RuntimeEventSink.NONE);
        assertThat(recorder.captureState().journalInstalled()).isFalse();
        assertThat(recorder.captureState().epoch()).isGreaterThan(resumed.epoch());
        assertThat(recorder.captureState().continuousSince()).containsEntry("first", 500L);
        time.set(600);
        long previous = recorder.captureState().epoch();
        recorder.setCorrelationContextProvider(() -> CorrelationContext.NONE);
        assertThat(recorder.captureState().epoch()).isGreaterThan(previous);
        assertThat(recorder.captureState().continuousSince())
                .containsEntry("first", 600L)
                .containsEntry("second", 600L);
    }

    @Test
    void aFailedKeyRepresentationInvalidatesOnlyItsManagerWithoutLosingPositiveDataOrExposingTheFailure() {
        AtomicLong time = new AtomicLong(100);
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10, time::get);
        recorder.setRuntimeEventSink(event -> true);
        recorder.markInstrumentedManager("first");
        recorder.markInstrumentedManager("second");
        recorder.recordHit("first", "prices", "retained-key");
        long epoch = recorder.captureState().epoch();
        recorder.recordMiss("first", "prices", new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("private key representation");
            }
        });
        CacheCapture proof = CacheCapture.of(recorder);
        assertThat(proof.epoch()).isGreaterThan(epoch);
        assertThat(proof.unavailable("first", 200)).isEqualTo(CacheCapture.OBSERVATION_FAILED);
        assertThat(proof.unavailable("second", 200)).isNull();
        assertThat(recorder.recentEvents()).hasSize(1);
        assertThat(proof.toString()).doesNotContain("private key representation", "retained-key");
        assertThatThrownBy(() -> recorder.recordMiss("second", "prices", new Object() {
                    @Override
                    public String toString() {
                        throw new AssertionError("fatal sentinel");
                    }
                }))
                .isInstanceOf(AssertionError.class)
                .hasMessage("fatal sentinel");
    }

    @Test
    void nullManagerNamesFailAtRegistrationRatherThanBreakingALaterSnapshot() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        var before = recorder.captureState();
        assertThatThrownBy(() -> recorder.markInstrumentedManager(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Cache manager name must not be null");
        assertThat(recorder.captureState()).isEqualTo(before);
    }

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
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace("trace-x", null));
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
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace("   ", null));
        recorder.recordHit("cacheManager", "orders", "1");
        assertThat(recorder.recentEvents().get(0).traceId()).isNull();
    }

    @Test
    void guardsAgainstThrowingProvider() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.setCorrelationContextProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        recorder.recordHit("cacheManager", "orders", "1");
        assertThat(recorder.recentEvents()).hasSize(1);
        assertThat(recorder.recentEvents().get(0).traceId()).isNull();
    }

    @Test
    void nullProviderRestoresDefault() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);
        recorder.setCorrelationContextProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        recorder.setCorrelationContextProvider(null);
        recorder.recordHit("cacheManager", "orders", "1");
        assertThat(recorder.recentEvents().get(0).traceId()).isNull();
    }

    @Test
    void isEnabledReflectsConstructorFlag() {
        assertThat(new CacheActivityRecorder(true, 10).isEnabled()).isTrue();
        assertThat(new CacheActivityRecorder(false, 10).isEnabled()).isFalse();
    }

    @Test
    void tracksWhetherAnAdapterInstrumentedACacheManager() {
        CacheActivityRecorder recorder = new CacheActivityRecorder(true, 10);

        assertThat(recorder.hasInstrumentedManager()).isFalse();

        recorder.markInstrumentedManager();

        assertThat(recorder.hasInstrumentedManager()).isTrue();
    }
}
