package io.github.jdubois.bootui.engine.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.TelemetrySettings;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.spi.InvocationContextProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LocalInvocationCaptureTests {

    private static final String TRACE = "1234567890abcdef1234567890abcdef";
    private static final String PARENT = "1234567890123456";
    private final TelemetrySettings settings = TelemetrySettings.of(true, true, 20, 500, 64);
    private final TelemetryStore store = new TelemetryStore(settings);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final LocalInvocationCapture capture = new LocalInvocationCapture(
            store, settings, new SelfTelemetryClassifier(true, "/console", "/console/api"), enabled::get, "sample");

    @Test
    void nestsLocalSpansAndCleansUpOnFailureWithoutRetainingThrowablePayloads() {
        var request = request();
        var outer = enter(request, "controller");
        String outerId = capture.current().invocationId();
        var inner = enter(request, "service");
        String innerId = capture.current().invocationId();
        capture.exit(inner, new IllegalStateException("secret request payload"));
        assertThat(capture.current().invocationId()).isEqualTo(outerId);
        capture.exit(outer, null);

        assertThat(capture.current()).isEqualTo(InvocationContextProvider.EMPTY);
        var spans = store.findTrace(TRACE).spans();
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).parentSpanId()).isEqualTo(outerId);
        assertThat(spans.get(0).spanId()).isEqualTo(innerId).hasSize(16);
        assertThat(spans.get(0).statusCode()).isEqualTo("ERROR");
        assertThat(spans.get(0).attributes().get("exception.type").asString())
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(spans.get(0).toString()).doesNotContain("secret request payload");
        assertThat(spans.get(1).parentSpanId()).isEqualTo(PARENT);
        assertThat(spans.get(1).isError()).isFalse();
        assertThat(spans).allSatisfy(span -> {
            assertThat(span.scope()).isEqualTo("bootui.explorer");
            assertThat(span.statusMessage()).isNull();
            assertThat(span.events()).isEmpty();
            assertThat(span.durationNanos()).isNotNegative();
        });
    }

    @Test
    void callBudgetIsSharedAcrossSequentialRootCallsAndCountsOmissions() {
        var request = request();
        for (int i = 0; i < 131; i++) {
            capture.exit(enter(request, "service"), null);
        }
        assertThat(store.findTrace(TRACE).spans()).hasSize(100);
        assertThat(store.findTrace(TRACE).omittedLocalSpans()).isEqualTo(31);
        assertThat(capture.current()).isEqualTo(InvocationContextProvider.EMPTY);
    }

    @Test
    void depthBudgetSuppressesExactParentageUntilOmittedCallsUnwind() {
        var request = request();
        List<LocalInvocationCapture.Invocation> scopes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            scopes.add(enter(request, "recursive"));
        }
        assertThat(capture.current()).isEqualTo(InvocationContextProvider.EMPTY);
        for (int i = scopes.size() - 1; i >= 0; i--) {
            capture.exit(scopes.get(i), null);
        }
        assertThat(store.findTrace(TRACE).spans()).hasSize(32);
        assertThat(store.findTrace(TRACE).omittedLocalSpans()).isEqualTo(8);
        assertThat(capture.current()).isEqualTo(InvocationContextProvider.EMPTY);
    }

    @Test
    void disabledUnsampledInvalidSelfAndIdleRequestsDoNotAllocateSpans() {
        assertThat(capture.request(TRACE, PARENT, false, "/orders")).isNull();
        assertThat(capture.request("bad-id", PARENT, true, "/orders")).isNull();
        assertThat(capture.request(TRACE, "0000000000000000", true, "/orders")).isNull();
        assertThat(capture.request(TRACE, PARENT, true, "/console/api/explorer"))
                .isNull();
        enabled.set(false);
        assertThat(request()).isNull();
        enabled.set(true);
        store.suspendForIdle();
        assertThat(request()).isNull();
        assertThat(store.allSpansSnapshot()).isEmpty();
    }

    @Test
    void clearIdleAndPolicyChangesDoNotResurrectOutstandingSpans() {
        var invocation = enter(request(), "service");
        store.clear();
        capture.exit(invocation, null);
        assertThat(store.allSpansSnapshot()).isEmpty();
        invocation = enter(request(), "service");
        enabled.set(false);
        capture.exit(invocation, null);
        enabled.set(true);
        assertThat(store.allSpansSnapshot()).isEmpty();
        assertThat(capture.current()).isEqualTo(InvocationContextProvider.EMPTY);
    }

    @Test
    void concurrentRequestsAndUnsupportedHandoffsNeverShareInvocationContext() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var origin = request();
        try {
            var first = executor.submit(() -> {
                assertThat(enter(origin, "foreignThread")).isNull();
                return captureOnWorker(TRACE);
            });
            var second = executor.submit(() -> captureOnWorker("2234567890abcdef1234567890abcdef"));
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(TRACE);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("2234567890abcdef1234567890abcdef");
            assertThat(store.allSpansSnapshot()).hasSize(2);
            assertThat(capture.current()).isEqualTo(InvocationContextProvider.EMPTY);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void metadataUsesExistingTelemetryTruncationPolicy() {
        capture.exit(enter(request(), "b".repeat(200)), null);
        NormalizedSpan span = store.allSpansSnapshot().get(0);
        assertThat(span.attributes().get("bootui.explorer.bean").asString())
                .isEqualTo("b".repeat(64) + "…[truncated 136 chars]");
    }

    private String captureOnWorker(String traceId) {
        var request = capture.request(traceId, PARENT, true, "/orders");
        var invocation = enter(request, "service");
        String actual = capture.current().traceId();
        capture.exit(invocation, null);
        assertThat(capture.current()).isEqualTo(InvocationContextProvider.EMPTY);
        return actual;
    }

    private LocalInvocationCapture.Request request() {
        return capture.request(TRACE, PARENT, true, "/orders");
    }

    private LocalInvocationCapture.Invocation enter(LocalInvocationCapture.Request request, String name) {
        return capture.enter(request, name, "example.Service", "load()", "SERVICE");
    }
}
