package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryStoreTests {

    private static NormalizedSpan span(String traceId, String spanId) {
        return new NormalizedSpan(
                traceId,
                spanId,
                null,
                "GET /sample",
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of(),
                List.of());
    }

    @Test
    void storeClampsConfiguredTraceCapacity() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 0, 500, 4096));

        store.add(span("trace-a", "span-a"));
        store.add(span("trace-b", "span-b"));

        assertThat(store.capacity()).isEqualTo(1);
        assertThat(store.retainedTraceCount()).isEqualTo(1);
        assertThat(store.findTrace("trace-a")).isNull();
        assertThat(store.findTrace("trace-b")).isNotNull();
    }

    @Test
    void storeClampsConfiguredSpanCapacity() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, Integer.MAX_VALUE, 4096));

        for (int i = 0; i < TelemetryStore.HARD_MAX_SPANS_PER_TRACE + 5; i++) {
            store.add(span("trace-a", "span-" + i));
        }

        assertThat(store.findTrace("trace-a").spans()).hasSize(TelemetryStore.HARD_MAX_SPANS_PER_TRACE);
    }

    @Test
    void suspendForIdleClearsAndStopsIngestionUntilResumed() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));
        assertThat(store.add(span("trace-a", "span-a"), false)).isTrue();
        assertThat(store.retainedTraceCount()).isEqualTo(1);

        store.suspendForIdle();
        assertThat(store.retainedTraceCount()).isZero();
        assertThat(store.add(span("trace-b", "span-b"), false)).isFalse();
        assertThat(store.retainedTraceCount()).isZero();

        store.resumeFromIdle();
        assertThat(store.add(span("trace-c", "span-c"), false)).isTrue();
        assertThat(store.retainedTraceCount()).isEqualTo(1);
    }
}
