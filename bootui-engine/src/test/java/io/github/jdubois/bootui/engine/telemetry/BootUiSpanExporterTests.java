package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BootUiSpanExporterTests {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    private static final SelfTelemetryClassifier SELF = new SelfTelemetryClassifier(true, "/bootui", "/bootui/api");

    private static final TelemetrySettings ENABLED = TelemetrySettings.of(true, true, 500, 500, 4096);

    private static final TelemetrySettings DISABLED = TelemetrySettings.of(false, true, 500, 500, 4096);

    @Test
    void exportsOpenTelemetrySpansToTheBootUiStore() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        SdkTracerProvider provider = tracerProvider(new BootUiSpanExporter(store, SELF, ENABLED));
        try {
            Tracer tracer = provider.get("bootui-test-scope");
            Span span = tracer.spanBuilder("GET /api/orders")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.route", "/api/orders")
                    .setAttribute("http.response.status_code", 200L)
                    .setAttribute(AttributeKey.stringArrayKey("sample.tags"), List.of("orders", "local"))
                    .startSpan();
            span.addEvent("controller.done", Attributes.of(AttributeKey.stringKey("sample.event"), "ok"));
            span.setStatus(StatusCode.OK);
            span.end();
            provider.forceFlush().join(1, TimeUnit.SECONDS);

            assertThat(store.retainedTraceCount()).isEqualTo(1);
            NormalizedSpan stored = store.recentTraces(1).get(0).spans().get(0);
            assertThat(stored.name()).isEqualTo("GET /api/orders");
            assertThat(stored.kind()).isEqualTo("SERVER");
            assertThat(stored.serviceName()).isEqualTo("sample-service");
            assertThat(stored.scope()).isEqualTo("bootui-test-scope");
            assertThat(stored.statusCode()).isEqualTo("OK");
            assertThat(stored.attributes().get("http.route").asString()).isEqualTo("/api/orders");
            assertThat(stored.attributes().get("http.response.status_code").asLong())
                    .isEqualTo(200L);
            assertThat(stored.attributes().get("sample.tags").value()).isEqualTo(List.of("orders", "local"));
            assertThat(stored.events()).singleElement().satisfies(event -> {
                assertThat(event.name()).isEqualTo("controller.done");
                assertThat(event.attributes().get("sample.event").asString()).isEqualTo("ok");
            });
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void dropsBootUiSelfSpansByDefault() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        SdkTracerProvider provider = tracerProvider(new BootUiSpanExporter(store, SELF, ENABLED));
        try {
            Span span = provider.get("bootui-test-scope")
                    .spanBuilder("GET /bootui/api/traces")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.route", "/bootui/api/traces")
                    .startSpan();
            span.end();
            provider.forceFlush().join(1, TimeUnit.SECONDS);

            assertThat(store.retainedTraceCount()).isZero();
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void doesNotCaptureSpansWhenTelemetryIsDisabled() {
        TelemetryStore store = new TelemetryStore(DISABLED);
        SdkTracerProvider provider = tracerProvider(new BootUiSpanExporter(store, SELF, DISABLED));
        try {
            Span span = provider.get("bootui-test-scope")
                    .spanBuilder("GET /api/orders")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.route", "/api/orders")
                    .startSpan();
            span.end();
            provider.forceFlush().join(1, TimeUnit.SECONDS);

            assertThat(store.retainedTraceCount()).isZero();
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void dropsChildSpansOfBootUiSelfTraces() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        SdkTracerProvider provider = tracerProvider(new BootUiSpanExporter(store, SELF, ENABLED));
        try {
            Tracer tracer = provider.get("bootui-test-scope");
            // The HTTP server span carries the /bootui path; the nested Spring Security filter-chain
            // span does not. With SimpleSpanProcessor the child is exported when it ends (before the
            // parent), mirroring how OpenTelemetry batches spans in production.
            Span root = tracer.spanBuilder("http get /bootui/api/traces")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.route", "/bootui/api/traces")
                    .startSpan();
            Span child = tracer.spanBuilder("security filterchain before")
                    .setParent(io.opentelemetry.context.Context.current().with(root))
                    .startSpan();
            child.end();
            root.end();
            provider.forceFlush().join(1, TimeUnit.SECONDS);

            assertThat(store.retainedTraceCount()).isZero();
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }

    private SdkTracerProvider tracerProvider(BootUiSpanExporter exporter) {
        return SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.of(SERVICE_NAME, "sample-service")))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }
}
