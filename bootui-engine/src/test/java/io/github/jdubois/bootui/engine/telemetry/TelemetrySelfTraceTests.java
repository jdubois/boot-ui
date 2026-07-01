package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetrySelfTraceTests {

    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    @Test
    void dropsChildSpanWhenRootSelfSpanArrivesFirst() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));

        assertThat(store.add(httpRoot(), true)).isFalse();
        assertThat(store.add(securityFilterChain(), false)).isFalse();

        assertThat(store.retainedTraceCount()).isZero();
        assertThat(store.findTrace(TRACE)).isNull();
    }

    @Test
    void purgesAlreadyStoredChildSpansWhenRootSelfSpanArrivesLater() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));

        // Spring Security "security filterchain before" ends (and is exported) before the
        // path-bearing HTTP server span that identifies the trace as BootUI's own traffic.
        assertThat(store.add(securityFilterChain(), false)).isTrue();
        assertThat(store.retainedTraceCount()).isEqualTo(1);

        assertThat(store.add(httpRoot(), true)).isFalse();

        assertThat(store.retainedTraceCount()).isZero();
        assertThat(store.findTrace(TRACE)).isNull();
    }

    @Test
    void keepsTracesThatAreNotBootUiTraffic() {
        TelemetryStore store = new TelemetryStore(TelemetrySettings.of(true, true, 500, 500, 4096));

        assertThat(store.add(span("app-trace", "root", null, "GET /api/orders", Map.of()), false))
                .isTrue();
        assertThat(store.add(span("app-trace", "child", "root", "security filterchain before", Map.of()), false))
                .isTrue();

        assertThat(store.retainedTraceCount()).isEqualTo(1);
        assertThat(store.findTrace("app-trace").spans()).hasSize(2);
    }

    private static NormalizedSpan httpRoot() {
        return span(TRACE, "1111111111111111", null, "http get /bootui/api/traces", Map.of());
    }

    private static NormalizedSpan securityFilterChain() {
        return span(
                TRACE,
                "2222222222222222",
                "1111111111111111",
                "security filterchain before",
                Map.of("spring.security.filterchain.position", AttributeValue.ofString("0/14")));
    }

    private static NormalizedSpan span(
            String traceId, String spanId, String parentSpanId, String name, Map<String, AttributeValue> attributes) {
        return new NormalizedSpan(
                traceId,
                spanId,
                parentSpanId,
                name,
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                attributes,
                List.of());
    }
}
