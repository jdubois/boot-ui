package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.TraceSummaryDto;
import io.github.jdubois.bootui.core.dto.TracesReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TracesServiceTests {

    private static final TelemetrySettings ENABLED = TelemetrySettings.of(true, true, 500, 500, 4096);

    private static final SelfTelemetryClassifier SELF = SelfTelemetryClassifier.forPaths("/bootui", "/bootui/api");

    @Test
    void summaryExposesHttpPathFromRootServerSpan() {
        NormalizedSpan root = new NormalizedSpan(
                "trace",
                "root",
                null,
                "security filterchain before",
                "INTERNAL",
                "sample",
                "test",
                1L,
                5L,
                "OK",
                null,
                Map.of("url.path", AttributeValue.ofString("/api/products/42?page=1")),
                List.of());

        TraceSummaryDto summary = TracesService.toSummary(bucketOf(root));

        assertThat(summary.rootSpanName()).isEqualTo("security filterchain before");
        assertThat(summary.httpPath()).isEqualTo("/api/products/42");
    }

    @Test
    void summaryFallsBackToServerSpanWhenRootHasNoPath() {
        NormalizedSpan root = new NormalizedSpan(
                "trace", "root", null, "GET", "INTERNAL", "sample", "test", 1L, 9L, "OK", null, Map.of(), List.of());
        NormalizedSpan server = new NormalizedSpan(
                "trace",
                "child",
                "root",
                "GET /api/orders",
                "SERVER",
                "sample",
                "test",
                2L,
                8L,
                "OK",
                null,
                Map.of("http.route", AttributeValue.ofString("/api/orders/{id}")),
                List.of());

        TraceSummaryDto summary = TracesService.toSummary(bucketOf(root, server));

        assertThat(summary.httpPath()).isEqualTo("/api/orders/{id}");
    }

    @Test
    void summaryLeavesHttpPathNullWhenNoPathAttributeIsPresent() {
        NormalizedSpan root = new NormalizedSpan(
                "trace",
                "root",
                null,
                "scheduled task",
                "INTERNAL",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of("code.function", AttributeValue.ofString("run")),
                List.of());

        TraceSummaryDto summary = TracesService.toSummary(bucketOf(root));

        assertThat(summary.rootSpanName()).isEqualTo("scheduled task");
        assertThat(summary.httpPath()).isNull();
    }

    @Test
    void listAndDetailHideBootUiSelfTraces() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        store.add(serverSpan("bootui-trace", "bootui-root", "GET /bootui/api/traces", "/bootui/api/traces"));
        store.add(serverSpan("host-trace", "host-root", "GET /api/orders", "/api/orders"));
        TracesService service = new TracesService(store, ENABLED, SELF);

        TracesReport report = service.list(50);

        assertThat(report.enabled()).isTrue();
        assertThat(report.retained()).isEqualTo(1);
        assertThat(report.traces())
                .singleElement()
                .satisfies(trace -> assertThat(trace.traceId()).isEqualTo("host-trace"));
        assertThat(service.detail("bootui-trace")).isEmpty();
        assertThat(service.detail("host-trace")).isPresent();
        assertThat(service.detail("does-not-exist")).isEmpty();
    }

    private static TelemetryStore.TraceBucket bucketOf(NormalizedSpan... spans) {
        TelemetryStore store = new TelemetryStore(ENABLED);
        String traceId = null;
        for (NormalizedSpan span : spans) {
            store.add(span);
            traceId = span.traceId();
        }
        return store.findTrace(traceId);
    }

    private static NormalizedSpan serverSpan(String traceId, String spanId, String name, String route) {
        return new NormalizedSpan(
                traceId,
                spanId,
                null,
                name,
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of("http.route", AttributeValue.ofString(route)),
                List.of());
    }
}
