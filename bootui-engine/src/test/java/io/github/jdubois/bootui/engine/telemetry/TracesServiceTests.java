package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SpanDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
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

    private static final SpanValueExposure MASKED = SpanValueExposure.current(policy(ValueExposure.MASKED, true));

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

        TraceSummaryDto summary = TracesService.toSummary(bucketOf(root), MASKED);

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

        TraceSummaryDto summary = TracesService.toSummary(bucketOf(root, server), MASKED);

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

        TraceSummaryDto summary = TracesService.toSummary(bucketOf(root), MASKED);

        assertThat(summary.rootSpanName()).isEqualTo("scheduled task");
        assertThat(summary.httpPath()).isNull();
    }

    @Test
    void listAndDetailHideBootUiSelfTraces() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        store.add(serverSpan("bootui-trace", "bootui-root", "GET /bootui/api/traces", "/bootui/api/traces"));
        store.add(serverSpan("host-trace", "host-root", "GET /api/orders", "/api/orders"));
        TracesService service = new TracesService(store, ENABLED, SELF, policy(ValueExposure.MASKED, true));

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
    void summaryMasksMatrixParametersOfTheHttpPath() {
        NormalizedSpan root = new NormalizedSpan(
                "trace",
                "root",
                null,
                "GET",
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of("url.path", AttributeValue.ofString("/cart;token=session-secret/items")),
                List.of());

        assertThat(TracesService.toSummary(bucketOf(root), MASKED).httpPath())
                .isEqualTo("/cart;token=" + SecretMasker.MASKED_VALUE + "/items");
    }

    @Test
    void summaryNeverReturnsTheAuthorityOfAnAbsoluteUrl() {
        assertThat(summaryPath("http://user:password@localhost:8080")).isEqualTo("/");
        assertThat(summaryPath("http://user:password@localhost:8080/orders?id=1"))
                .isEqualTo("/orders");
        assertThat(summaryPath("http://us/er:pass word@local host/orders?id=1")).isEqualTo("/orders");
        assertThat(summaryPath("http://user:pass word@local host")).isEqualTo("/");
    }

    private static String summaryPath(String url) {
        NormalizedSpan root = new NormalizedSpan(
                "trace",
                "root",
                null,
                "GET",
                "CLIENT",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of("url.full", AttributeValue.ofString(url)),
                List.of());
        return TracesService.toSummary(bucketOf(root), MASKED).httpPath();
    }

    @Test
    void detailAppliesTheLiveExposurePolicyOnEveryRead() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("http.route", AttributeValue.ofString("/api/sample/boom"));
        store.add(new NormalizedSpan(
                "trace",
                "root",
                null,
                "GET /api/sample/boom",
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "ERROR",
                "Boom: apiToken=sample-secret-token",
                attributes,
                List.of(new NormalizedEvent(
                        "exception",
                        1L,
                        Map.of(
                                "exception.type",
                                AttributeValue.ofString("java.lang.IllegalStateException"),
                                "exception.message",
                                AttributeValue.ofString("Boom: apiToken=sample-secret-token"),
                                "exception.stacktrace",
                                AttributeValue.ofString(
                                        "java.lang.IllegalStateException: Boom: apiToken=sample-secret-token\n"
                                                + "\tat com.example.Sample.boom(Sample.java:42)"))))));
        MutablePolicy policy = new MutablePolicy(ValueExposure.FULL);
        TracesService service = new TracesService(store, ENABLED, SELF, policy);

        SpanDto full = span(service.detail("trace").orElseThrow());
        assertThat(full.statusMessage()).isEqualTo("Boom: apiToken=sample-secret-token");
        assertThat(eventAttribute(full, "exception.message")).isEqualTo("Boom: apiToken=sample-secret-token");

        policy.exposure = ValueExposure.MASKED;
        TraceDetailDto masked = service.detail("trace").orElseThrow();
        assertThat(masked.toString()).doesNotContain("sample-secret-token");
        assertThat(span(masked).statusMessage()).isEqualTo("Boom: apiToken=" + SecretMasker.MASKED_VALUE);
        assertThat(eventAttribute(span(masked), "exception.message"))
                .isEqualTo("Boom: apiToken=" + SecretMasker.MASKED_VALUE);
        assertThat((String) eventAttribute(span(masked), "exception.stacktrace"))
                .startsWith("java.lang.IllegalStateException: Boom: apiToken=" + SecretMasker.MASKED_VALUE)
                .contains("Sample.boom(Sample.java:42)");

        policy.exposure = ValueExposure.METADATA_ONLY;
        TraceDetailDto metadata = service.detail("trace").orElseThrow();
        assertThat(metadata.toString()).doesNotContain("sample-secret-token", "Boom");
        assertThat(span(metadata).statusMessage()).isNull();
        assertThat(span(metadata).statusCode()).isEqualTo("ERROR");
        assertThat(eventAttribute(span(metadata), "exception.message")).isNull();
        assertThat(eventAttribute(span(metadata), "exception.stacktrace")).isNull();
        assertThat(eventAttribute(span(metadata), "exception.type")).isEqualTo("java.lang.IllegalStateException");
        assertThat(span(metadata).events().get(0).name()).isEqualTo("exception");
    }

    @Test
    void spanReadsMaskNestedValuesWithoutChangingStoredEvidence() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("auth.token", AttributeValue.ofString("example-value"));
        attributes.put("http.request.header.authorization", AttributeValue.ofList(List.of("Basic example-value")));
        attributes.put("notes", AttributeValue.ofList(List.of("token=example-value", "safe")));
        attributes.put(
                "headers",
                new AttributeValue(
                        "map",
                        Map.of(
                                "authorization",
                                "Basic example-value",
                                "nested",
                                List.of(Map.of("password", "example-value")))));
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
                List.of(new NormalizedEvent("exception", 2L, Map.of("details", attributes.get("headers"))))));
        MutablePolicy policy = new MutablePolicy(ValueExposure.MASKED);
        TracesService service = new TracesService(store, ENABLED, SELF, policy);

        SpanDto masked = span(service.detail("trace").orElseThrow());
        assertThat(masked.statusMessage()).isEqualTo("failed token=******");
        assertThat(masked.attributes())
                .extracting(attribute -> attribute.value())
                .containsExactly(
                        "******",
                        List.of("******"),
                        List.of("token=******", "safe"),
                        Map.of("authorization", "******", "nested", List.of(Map.of("password", "******"))));
        assertThat(eventAttribute(masked, "details"))
                .isEqualTo(Map.of("authorization", "******", "nested", List.of(Map.of("password", "******"))));

        policy.exposure = ValueExposure.METADATA_ONLY;
        SpanDto metadata = span(service.detail("trace").orElseThrow());
        assertThat(metadata.statusMessage()).isNull();
        assertThat(metadata.attributes()).hasSize(attributes.size());
        assertThat(metadata.events()).hasSize(1);
        assertThat(service.list(10).traces().get(0).services()).containsExactly("sample");

        policy.exposure = ValueExposure.FULL;
        SpanDto full = span(service.detail("trace").orElseThrow());
        assertThat(full.statusMessage()).isEqualTo("failed token=example-value");
        assertThat(eventAttribute(full, "details"))
                .isEqualTo(attributes.get("headers").value());

        policy.exposure = ValueExposure.MASKED;
        policy.mask = false;
        assertThat(span(service.detail("trace").orElseThrow()).statusMessage()).isEqualTo("failed token=example-value");
    }

    private static SpanDto span(TraceDetailDto detail) {
        assertThat(detail.spans()).hasSize(1);
        return detail.spans().get(0);
    }

    private static Object eventAttribute(SpanDto span, String key) {
        return span.events().get(0).attributes().stream()
                .filter(attribute -> attribute.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing event attribute " + key))
                .value();
    }

    static ExposurePolicy policy(ValueExposure exposure, boolean maskSecrets) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
    }

    private static final class MutablePolicy implements ExposurePolicy {

        private ValueExposure exposure;
        private boolean mask = true;

        private MutablePolicy(ValueExposure exposure) {
            this.exposure = exposure;
        }

        @Override
        public ValueExposure valueExposure() {
            return exposure;
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
