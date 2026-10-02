package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies the BootUI OpenTelemetry enrichment path end-to-end through a real SDK tracer: the identity
 * {@code SpanProcessor} stamps {@code bootui.enriched}/service/instance on span start, and the
 * {@link OtelSpanEnricher} accumulates {@code bootui.sql.*}/{@code bootui.exception.*} depth on the active
 * span, all read back from a stored {@link NormalizedSpan}.
 */
class SpanEnrichmentTests {

    private static final SelfTelemetryClassifier SELF = new SelfTelemetryClassifier(true, "/bootui", "/bootui/api");

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
    void anAiSpanStartedInARequestIsRecordedUnderItEvenWhenExportedOnAnotherThread() throws Exception {
        TelemetryStore store = new TelemetryStore(ENRICH_OFF);
        List<RuntimeEvent> published = new ArrayList<>();
        store.setRuntimeEventSink(published::add);
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.empty()))
                .addSpanProcessor(new BootUiIdentitySpanProcessor(ENRICH_OFF, "orders-service", "pod-7", store))
                .addSpanProcessor(SimpleSpanProcessor.create(new BootUiSpanExporter(store, SELF, ENRICH_OFF)))
                .build();
        try {
            Span inRequest;
            try (BootUiCorrelation.Scope ignored =
                    BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
                inRequest = chat(provider);
            }
            Span outside = chat(provider);
            Span ofBootUi;
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
                ofBootUi = chat(provider);
            }
            // Ended, and so exported, on another thread, as a batch exporter would.
            Thread exporter = new Thread(() -> {
                inRequest.end();
                outside.end();
                ofBootUi.end();
            });
            exporter.start();
            exporter.join();
            provider.forceFlush().join(1, TimeUnit.SECONDS);

            assertThat(published)
                    .extracting(RuntimeEvent::requestId)
                    .containsExactlyInAnyOrder("0123456789abcdef", null, null);
            assertThat(published)
                    .allSatisfy(event -> assertThat(event.traceId()).isNotNull());
            assertThat(store.spanOwners()).as("forgotten once exported").isZero();
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void noOwnerIsRememberedWhileCaptureIsOffOrTheJournalDoesNotRecordAiCalls() {
        TelemetryStore off = new TelemetryStore(TelemetrySettings.of(false, false, 500, 500, 4096));
        off.setRuntimeEventSink(event -> true);
        TelemetryStore notRecording = new TelemetryStore(ENABLED);

        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
            off.spanStarted("trace-1", "span-1");
            notRecording.spanStarted("trace-1", "span-1");
        }

        assertThat(off.spanOwners()).isZero();
        assertThat(notRecording.spanOwners()).isZero();
    }

    private static Span chat(SdkTracerProvider provider) {
        return provider.get("test")
                .spanBuilder("chat gpt-4o")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("gen_ai.operation.name", "chat")
                .setAttribute("gen_ai.system", "openai")
                .startSpan();
    }

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
            Span span = provider.get("test")
                    .spanBuilder("GET /api/orders")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            try (Scope scope = span.makeCurrent()) {
                enricher.onSqlStatement(() -> false);
                enricher.onSqlStatement(() -> true);
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
            assertThat(stored.attributes()
                            .get(BootUiSpanAttributes.SQL_N_PLUS_ONE)
                            .value())
                    .isEqualTo(true);
            assertThat(stored.attributes().get(BootUiSpanAttributes.EXCEPTIONS).asLong())
                    .isEqualTo(1L);
            assertThat(stored.attributes()
                            .get(BootUiSpanAttributes.EXCEPTION_TYPE)
                            .asString())
                    .isEqualTo("java.lang.IllegalStateException");
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void enricherIsInertWithoutAnActiveSpan() {
        OtelSpanEnricher enricher = new OtelSpanEnricher(ENABLED);
        // No current span: the invalid span context short-circuits without throwing.
        enricher.onSqlStatement(() -> true);
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
            Span span = provider.get("test")
                    .spanBuilder("GET /api/orders")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            try (Scope scope = span.makeCurrent()) {
                enricher.onSqlStatement(() -> true);
                enricher.onException("java.lang.IllegalStateException");
            } finally {
                span.end();
            }
            provider.forceFlush().join(1, TimeUnit.SECONDS);

            NormalizedSpan stored = store.recentTraces(1).get(0).spans().get(0);
            assertThat(stored.attributes().get(BootUiSpanAttributes.ENRICHED)).isNull();
            assertThat(stored.attributes().get(BootUiSpanAttributes.SQL_QUERIES))
                    .isNull();
            assertThat(stored.attributes().get(BootUiSpanAttributes.EXCEPTIONS)).isNull();
            assertThat(enricher.enabled()).isFalse();
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void noOpEnricherIsDisabledAndSwallowsCalls() {
        assertThat(SpanEnricher.NO_OP.enabled()).isFalse();
        // No-op must never throw regardless of arguments, even with a supplier that would throw if invoked.
        SpanEnricher.NO_OP.onSqlStatement(() -> {
            throw new IllegalStateException("supplier must not be evaluated by the no-op enricher");
        });
        SpanEnricher.NO_OP.onException("java.lang.RuntimeException");
    }

    @Test
    void nPlusOneSupplierIsEvaluatedOnlyUntilTheSpanIsFlagged() {
        OtelSpanEnricher enricher = new OtelSpanEnricher(ENABLED);
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.empty()))
                .build();
        java.util.concurrent.atomic.AtomicInteger evaluations = new java.util.concurrent.atomic.AtomicInteger();
        try {
            Span span = provider.get("test")
                    .spanBuilder("GET /api/orders")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            try (Scope scope = span.makeCurrent()) {
                // First statement: not yet suspected — supplier evaluated, stays unflagged.
                enricher.onSqlStatement(() -> {
                    evaluations.incrementAndGet();
                    return false;
                });
                // Second statement: suspected — supplier evaluated, flags the span sticky.
                enricher.onSqlStatement(() -> {
                    evaluations.incrementAndGet();
                    return true;
                });
                // Third statement: already flagged — supplier must be skipped entirely.
                enricher.onSqlStatement(() -> {
                    evaluations.incrementAndGet();
                    return true;
                });
            } finally {
                span.end();
            }
            assertThat(evaluations.get()).isEqualTo(2);
        } finally {
            provider.shutdown().join(1, TimeUnit.SECONDS);
        }
    }
}
