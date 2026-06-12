package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.otlp.AttributeValue;
import io.github.jdubois.bootui.autoconfigure.otlp.NormalizedSpan;
import io.github.jdubois.bootui.autoconfigure.otlp.TelemetryStore;
import io.github.jdubois.bootui.core.dto.TraceSummaryDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class TracesControllerTests {

    @Test
    void listAndDetailHideBootUiSelfTraces() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(properties.getTelemetry());
        store.add(span("bootui-trace", "bootui-root", null, "GET /bootui/api/traces", "/bootui/api/traces"));
        store.add(span("host-trace", "host-root", null, "GET /api/orders", "/api/orders"));

        MockMvc mvc = standaloneSetup(new TracesController(store, properties)).build();

        mvc.perform(get("/bootui/api/traces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retained").value(1))
                .andExpect(jsonPath("$.traces.length()").value(1))
                .andExpect(jsonPath("$.traces[0].traceId").value("host-trace"))
                .andExpect(jsonPath("$.traces[0].httpPath").value("/api/orders"));

        mvc.perform(get("/bootui/api/traces/bootui-trace")).andExpect(status().isNotFound());
        mvc.perform(get("/bootui/api/traces/host-trace")).andExpect(status().isOk());
    }

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

        TraceSummaryDto summary = TracesController.toSummary(bucketOf(root));

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

        TraceSummaryDto summary = TracesController.toSummary(bucketOf(root, server));

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

        TraceSummaryDto summary = TracesController.toSummary(bucketOf(root));

        assertThat(summary.rootSpanName()).isEqualTo("scheduled task");
        assertThat(summary.httpPath()).isNull();
    }

    private static TelemetryStore.TraceBucket bucketOf(NormalizedSpan... spans) {
        TelemetryStore store = new TelemetryStore(new BootUiProperties().getTelemetry());
        String traceId = null;
        for (NormalizedSpan span : spans) {
            store.add(span);
            traceId = span.traceId();
        }
        return store.findTrace(traceId);
    }

    private static NormalizedSpan span(String traceId, String spanId, String parentSpanId, String name, String route) {
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
                Map.of("http.route", AttributeValue.ofString(route)),
                List.of());
    }
}
