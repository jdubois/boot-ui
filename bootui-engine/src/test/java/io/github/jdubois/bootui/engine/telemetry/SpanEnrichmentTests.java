package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies the BootUI OpenTelemetry enrichment path end-to-end through a real SDK tracer: the identity
 * {@code SpanProcessor} stamps {@code bootui.enriched}/service/instance on span start, and the
 * {@link OtelSpanEnricher} accumulates {@code bootui.sql.*}/{@code bootui.exception.*} depth on the active
 * span, all read back from a stored {@link NormalizedSpan}.
 */
class SpanEnrichmentTests {

    private static final SelfTelemetryClassifier SELF = SelfTelemetryClassifier.forPaths("/bootui", "/bootui/api");

    private static final TelemetrySettings ENABLED = TelemetrySettings.of(true, false, 500, 500, 4096);

    private static final TelemetrySettings ENRICH_OFF = new TelemetrySettings() {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean excludeSelfSpans() {
            return false;
        }

        @Override
        public int maxTraces() {
            return 500;
        }

        @Override
        public int maxSpansPerTrace() {
            return 500;
        }

        @Override
        public int maxAttributeValueBytes() {
            return 4096;
        }

        @Override
        public boolean enrichmentEnabled() {
            return false;
        }
    };

    @Test
    void stampsIdentityAndAccumulatesDepthOnTheActiveSpan() {
        TelemetryStore store = new TelemetryStore(ENABLED);
        OtelSpanEnricher enricher = new OtelSpanEnricher(ENABLED);
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.empty()))
                .addSpanProcessor(new BootUiIdentitySpanProcessor(ENABLED, "orders-service", "pod-7"))
                .addSpanProcessor(SimpleSpanProcessor.create(new BootUiSpanExporter(store, SELF, ENABLED)))
                .build();
        try {
            Span span = provider.get("test").spanBuilder("GET /api/orders")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            try (Scope scope = span.makeCurrent()) {
                enricher.onSqlStatement(false);
                enricher.onSqlStatement(true);
                enricher.onException("java.lang.IllegalStateException");
            } finally {
                span.end();
            }
            provider.forceFlush().join(1, TimeUnit.SECONDS);

            NormalizedSpan stored = store.recentTraces(1).get(0).spans().get(0);
            assertThat(stored.attributes().get(BootUiSpanAttributes.ENRICHED).value())
                    .isEqualTo(true);
            assertThat(stored.attributes().get(BootUiSpanAttributes.SERVICE).asString())
                    .isEqualTo("orders-service");
            assertThat(stored.attributes().get(BootUiSpanAttributes.INSTANCE).asString())
                    .isEqualTo("pod-7");
            assertThat(stored.attributes().get(BootUiSpanAttributes.SQL_QUERIES).asLong())
                    .isEqualTo(2L);
            assertThat(stored.attributes().get(BootUiSpanAttributes.SQL_N_PLUS_ONE).value())
                    .isEqualTo(true);
            assertThat(stored.attributes().get(BootUiSpanAttributes.EXCEPTIONS).asLong())
                    .isEqualTo(1L);
            assertThat(stored.attributes().get(BootUiSpanAttributes.EXCEPTION_TYPE).asString())
                    .isEqualTo("java.lang.IllegalStateException");
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void enricherIsInertWithoutAnActiveSpan() {
        OtelSpanEnricher enricher = new OtelSpanEnricher(ENABLED);
        // No current span: the invalid span context short-circuits without throwing.
        enricher.onSqlStatement(true);
        enricher.onException("java.lang.RuntimeException");
        assertThat(enricher.enabled()).isTrue();
    }

    @Test
    void enrichmentDisabledLeavesSpansUnstamped() {
        TelemetryStore store = new TelemetryStore(ENRICH_OFF);
        OtelSpanEnricher enricher = new OtelSpanEnricher(ENRICH_OFF);
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.empty()))
                .addSpanProcessor(new BootUiIdentitySpanProcessor(ENRICH_OFF, "orders-service", "pod-7"))
                .addSpanProcessor(SimpleSpanProcessor.create(new BootUiSpanExporter(store, SELF, ENRICH_OFF)))
                .build();
        try {
            Span span = provider.get("test").spanBuilder("GET /api/orders")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            try (Scope scope = span.makeCurrent()) {
                enricher.onSqlStatement(true);
                enricher.onException("java.lang.IllegalStateException");
            } finally {
                span.end();
            }
            provider.forceFlush().join(1, TimeUnit.SECONDS);

            NormalizedSpan stored = store.recentTraces(1).get(0).spans().get(0);
            assertThat(stored.attributes().get(BootUiSpanAttributes.ENRICHED)).isNull();
            assertThat(stored.attributes().get(BootUiSpanAttributes.SQL_QUERIES)).isNull();
            assertThat(stored.attributes().get(BootUiSpanAttributes.EXCEPTIONS)).isNull();
            assertThat(enricher.enabled()).isFalse();
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }
}
