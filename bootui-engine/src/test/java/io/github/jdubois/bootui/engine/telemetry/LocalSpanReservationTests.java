package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalSpanReservationTests {

    @Test
    void reservesHostCapacityBeforeAllocationAndNeverLabelsLocalCallsAsHttpRoots() {
        var settings = TelemetrySettings.of(true, true, 5, 4, 100);
        var store = new TelemetryStore(settings);
        var one = store.reserveLocalSpan("trace");
        var two = store.reserveLocalSpan("trace");
        var three = store.reserveLocalSpan("trace");
        assertThat(store.reserveLocalSpan("trace")).isNull();
        assertThat(store.recentTraces(10)).isEmpty();
        store.completeLocalSpan(two, local("two"));
        store.completeLocalSpan(three, local("three"));
        store.completeLocalSpan(one, local("one"));
        assertThat(TracesService.toSummary(store.findTrace("trace")).rootSpanName())
                .isNull();
        store.add(host("root", null));
        assertThat(store.findTrace("trace").spans()).hasSize(4);
        assertThat(TracesService.toSummary(store.findTrace("trace")).rootSpanName())
                .isEqualTo("HTTP root");
        store.add(host("late", "root"));
        assertThat(store.findTrace("trace").spans()).hasSize(4);
        assertThat(store.findTrace("trace").omittedLocalSpans()).isEqualTo(1);
        assertThat(store.findTrace("trace").spans())
                .extracting(NormalizedSpan::spanId)
                .contains("root", "late");
    }

    @Test
    void selfClassificationAndClearInvalidateOutstandingReservations() {
        var store = new TelemetryStore(TelemetrySettings.of(true, true, 5, 20, 100));
        var reservation = store.reserveLocalSpan("trace");
        store.clear();
        assertThat(store.completeLocalSpan(reservation, local("one"))).isFalse();
        reservation = store.reserveLocalSpan("trace");
        store.add(host("root", null), true);
        assertThat(store.completeLocalSpan(reservation, local("two"))).isFalse();
        assertThat(store.reserveLocalSpan("trace")).isNull();
        assertThat(store.retainedTraceCount()).isZero();
    }

    @Test
    void capacityOneKeepsOnlyTheRealHttpRootAndExposesOmissionMetadata() {
        var store = new TelemetryStore(TelemetrySettings.of(true, true, 5, 1, 100));
        assertThat(store.reserveLocalSpan("trace")).isNull();
        store.omitLocalSpan("trace");
        store.add(host("root", null));
        assertThat(store.findTrace("trace").spans()).hasSize(1);
        assertThat(store.findTrace("trace").omittedLocalSpans()).isEqualTo(1);
    }

    private static NormalizedSpan local(String id) {
        return new NormalizedSpan(
                "trace",
                id,
                "root",
                "service.load()",
                "INTERNAL",
                "sample",
                "bootui.explorer",
                2,
                3,
                "UNSET",
                null,
                Map.of(),
                List.of());
    }

    private static NormalizedSpan host(String id, String parent) {
        return new NormalizedSpan(
                "trace", id, parent, "HTTP root", "SERVER", "sample", "host", 1, 4, "UNSET", null, Map.of(), List.of());
    }
}
