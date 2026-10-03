package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.TraceSummaryDto;
import io.github.jdubois.bootui.core.dto.TracesReport;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TracesServiceTests {

    private static final TelemetrySettings ENABLED = TelemetrySettings.of(true, true, 500, 500, 4096);

    private static final SelfTelemetryClassifier SELF = new SelfTelemetryClassifier(true, "/bootui", "/bootui/api");

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
        TracesService service = new TracesService(store, ENABLED, SELF, new MutableExposure());

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

    @Test
    void spanReadsApplyTheCurrentPolicyWithoutChangingStoredEvidence() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("http.route", AttributeValue.ofString("/api/orders"));
        attributes.put("auth.token", AttributeValue.ofString("example-value"));
        attributes.put("http.request.header.authorization", AttributeValue.ofList(List.of("Basic example-value")));
        attributes.put("exception.message", AttributeValue.ofString("failed token=example-value"));
        attributes.put("notes", AttributeValue.ofList(List.of("token=example-value", "safe")));
        store.add(new NormalizedSpan(
                "trace",
                "span",
                null,
                "GET /api/orders",
                "SERVER",
                "sample",
                "test",
                1L,
                5L,
                "ERROR",
                "failed token=example-value",
                attributes,
                List.of(new NormalizedEvent(
                        "exception token=example-value",
                        2L,
                        Map.of("exception.message", AttributeValue.ofString("token=example-value"))))));
        MutableExposure exposure = new MutableExposure();
        TracesService service = new TracesService(store, ENABLED, SELF, exposure);

        var masked = service.detail("trace").orElseThrow().spans().get(0);
        assertThat(masked.statusMessage()).isEqualTo("failed token=******");
        assertThat(masked.attributes().stream()
                        .filter(attribute -> attribute.key().equals("auth.token"))
                        .findFirst()
                        .orElseThrow()
                        .value())
                .isEqualTo("******");
        assertThat(masked.attributes().stream()
                        .filter(attribute -> attribute.key().equals("http.request.header.authorization"))
                        .findFirst()
                        .orElseThrow()
                        .value())
                .isEqualTo("******");
        assertThat(masked.events().get(0).name()).isEqualTo("exception token=******");
        assertThat(masked.events().get(0).attributes().get(0).value()).isEqualTo("token=******");
        assertThat(masked.attributes().stream()
                        .filter(attribute -> attribute.key().equals("notes"))
                        .findFirst()
                        .orElseThrow()
                        .value())
                .isEqualTo(List.of("token=******", "safe"));
        assertThat(masked.attributes().stream()
                        .filter(attribute -> attribute.key().equals("http.route"))
                        .findFirst()
                        .orElseThrow()
                        .value())
                .isEqualTo("/api/orders");

        exposure.value = ValueExposure.METADATA_ONLY;
        var metadata = service.detail("trace").orElseThrow().spans().get(0);
        assertThat(metadata.statusMessage()).isNull();
        assertThat(metadata.attributes()).isEmpty();
        assertThat(metadata.events()).isEmpty();
        assertThat(service.list(10).traces().get(0).services()).isEmpty();
        assertThat(service.list(10).traces().get(0).rootSpanName()).isNull();

        exposure.value = ValueExposure.FULL;
        var full = service.detail("trace").orElseThrow().spans().get(0);
        assertThat(full.statusMessage()).isEqualTo("failed token=example-value");
        assertThat(full.attributes().stream()
                        .filter(attribute -> attribute.key().equals("auth.token"))
                        .findFirst()
                        .orElseThrow()
                        .value())
                .isEqualTo("example-value");
        assertThat(full.events().get(0).name()).isEqualTo("exception token=example-value");

        exposure.value = ValueExposure.MASKED;
        exposure.mask = false;
        assertThat(service.detail("trace").orElseThrow().spans().get(0).statusMessage())
                .isEqualTo("failed token=example-value");
    }

    @Test
    void aiChatDetailsShareTheSameLiveSpanExposureRule() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        store.add(new NormalizedSpan(
                "ai-trace",
                "chat-span",
                null,
                "chat",
                "CLIENT",
                "sample",
                "test",
                1L,
                5L,
                "OK",
                null,
                Map.of(
                        "gen_ai.operation.name", AttributeValue.ofString("chat"),
                        "gen_ai.request.model", AttributeValue.ofString("example-model"),
                        "gen_ai.prompt", AttributeValue.ofString("token=example-value")),
                List.of()));
        MutableExposure exposure = new MutableExposure();
        AiUsageService service = new AiUsageService(
                store, () -> new AiUsageSettings(true, 10, 60, false), System::currentTimeMillis, exposure);

        assertThat(service.chatDetail("chat-span").orElseThrow().attributes().stream()
                        .filter(attribute -> attribute.key().equals("gen_ai.prompt"))
                        .findFirst()
                        .orElseThrow()
                        .value())
                .isEqualTo("token=******");
        exposure.value = ValueExposure.METADATA_ONLY;
        assertThat(service.chatDetail("chat-span").orElseThrow().attributes()).isEmpty();
        assertThat(service.chats(10).get(0).requestModel()).isNull();
        assertThat(service.overview().tokensByModel()).isEmpty();
        exposure.value = ValueExposure.FULL;
        assertThat(service.chatDetail("chat-span").orElseThrow().attributes().stream()
                        .filter(attribute -> attribute.key().equals("gen_ai.prompt"))
                        .findFirst()
                        .orElseThrow()
                        .value())
                .isEqualTo("token=example-value");
    }

    private static final class MutableExposure implements ExposurePolicy {

        private ValueExposure value = ValueExposure.MASKED;
        private boolean mask = true;

        @Override
        public ValueExposure valueExposure() {
            return value;
        }

        @Override
        public boolean maskSecrets() {
            return mask;
        }
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
