package io.github.jdubois.bootui.quarkus4;

import io.github.jdubois.bootui.engine.telemetry.BootUiIdentitySpanProcessor;
import io.github.jdubois.bootui.engine.telemetry.BootUiSpanExporter;
import io.github.jdubois.bootui.engine.telemetry.OtelSpanEnricher;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.SpanEnricher;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.Config;

/**
 * OpenTelemetry capture wiring for the Quarkus adapter: produces the in-process {@link SpanProcessor} that
 * feeds finished spans into the engine {@link TelemetryStore} via the engine {@link BootUiSpanExporter}.
 *
 * <p>This class is the Quarkus analogue of the Spring adapter's {@code BootUiOpenTelemetryConfiguration}.
 * Quarkus OpenTelemetry auto-discovers CDI {@code SpanProcessor} beans and adds them to its
 * {@code TracerProvider}, so wrapping the engine exporter in a {@link SimpleSpanProcessor} is all that is
 * needed to capture spans in-process — no OTLP receiver and zero network, matching
 * {@code quarkus.otel.traces.exporter=none}. Processor discovery can precede the native runtime freeze:
 * callbacks remain registered, but store/exporter construction waits for an admitted span. The host's other
 * processors and exporters are never replaced.</p>
 *
 * <p><strong>It is deliberately not annotated with a CDI scope, and the deployment processor excludes it
 * from bean discovery when OpenTelemetry is absent.</strong> The extension runtime jar is Jandex-indexed
 * (so Arc discovers the always-on beans), and Arc treats a {@code @Produces} method as bean-defining — so
 * this producer is discovered whenever the jar is indexed, and Arc would try to resolve its
 * {@code SpanProcessor} return type even in an application without {@code quarkus-opentelemetry}, linking
 * the OTel SDK that must stay absent (R2/BF2). The processor therefore actively excludes this class from
 * discovery unless the OpenTelemetry-tracer capability is present (see
 * {@code BootUiQuarkusProcessor#registerOpenTelemetryCapture}). The engine exporter keeps its own live
 * {@code settings.enabled()} short-circuit, so a registered-but-disabled pipeline still drops everything
 * (BF4).</p>
 */
public class BootUiOtelProducer {

    @Produces
    @Singleton
    public SpanProcessor bootUiSpanProcessor(
            Instance<TelemetryStore> store,
            QuarkusTelemetrySettings settings,
            Instance<SelfTelemetryClassifier> selfClassifier) {
        // Capture-side classification (BF1) now shares the same instance as every transform/display
        // consumer — produced once in BootUiTelemetryProducer — instead of building its own.
        return new DeferredSpanProcessor(
                settings,
                false,
                true,
                () -> SimpleSpanProcessor.create(new BootUiSpanExporter(store.get(), selfClassifier.get(), settings)));
    }

    public SpanProcessor bootUiSpanProcessor(
            TelemetryStore store, QuarkusTelemetrySettings settings, SelfTelemetryClassifier selfClassifier) {
        return new DeferredSpanProcessor(
                settings,
                false,
                true,
                () -> SimpleSpanProcessor.create(new BootUiSpanExporter(store, selfClassifier, settings)));
    }

    /**
     * Observes every span at start: it tells the telemetry store which BootUI request or execution started the
     * span, so an AI call nests under its request by id in the runtime journal, and stamps BootUI identity
     * attributes ({@code bootui.enriched}/service/instance) so a cross-service trace waterfall can attribute each
     * service's depth. Quarkus OpenTelemetry auto-discovers this as a second {@link SpanProcessor} bean; it
     * re-reads the live capture and enrichment toggles, so identity stays off when
     * {@code bootui.telemetry.enrich=false}. Concentrated here with the other OTel-touching capture beans and
     * excluded from discovery when OpenTelemetry is absent.
     */
    @Produces
    @Singleton
    public SpanProcessor bootUiIdentitySpanProcessor(
            QuarkusTelemetrySettings settings, Config config, Instance<TelemetryStore> store) {
        return deferredIdentity(settings, config, store::get);
    }

    public SpanProcessor bootUiIdentitySpanProcessor(
            QuarkusTelemetrySettings settings, Config config, TelemetryStore store) {
        return deferredIdentity(settings, config, () -> store);
    }

    private static SpanProcessor deferredIdentity(
            QuarkusTelemetrySettings settings, Config config, Supplier<TelemetryStore> store) {
        return new DeferredSpanProcessor(
                settings,
                true,
                false,
                () -> new BootUiIdentitySpanProcessor(
                        settings,
                        config.getOptionalValue("quarkus.application.name", String.class)
                                .orElse(null),
                        System.getenv("HOSTNAME"),
                        store.get()));
    }

    /**
     * The capture-time enricher that stamps {@code bootui.sql.*}/{@code bootui.exception.*} depth on the
     * active span. The SQL Trace recorder and exception store inject it via {@code Instance<SpanEnricher>}
     * and install it when resolvable; absent OpenTelemetry this producer is excluded, so those capture points
     * resolve no enricher and keep the neutral no-op (mirrors the {@code TraceIdSource} seam).
     */
    @Produces
    @Singleton
    public SpanEnricher bootUiSpanEnricher(QuarkusTelemetrySettings settings) {
        return new OtelSpanEnricher(settings);
    }

    /**
     * Quarkus can discover processors before native activation freezes. Required callbacks stay registered;
     * the store/exporter is created only by the first admitted callback, never by flush or shutdown.
     */
    private static final class DeferredSpanProcessor implements SpanProcessor {

        private final QuarkusTelemetrySettings settings;
        private final boolean startRequired;
        private final boolean endRequired;
        private final Supplier<SpanProcessor> factory;
        private SpanProcessor delegate;
        private boolean closed;

        private DeferredSpanProcessor(
                QuarkusTelemetrySettings settings,
                boolean startRequired,
                boolean endRequired,
                Supplier<SpanProcessor> factory) {
            this.settings = settings;
            this.startRequired = startRequired;
            this.endRequired = endRequired;
            this.factory = factory;
        }

        @Override
        public synchronized void onStart(Context parentContext, ReadWriteSpan span) {
            if (startRequired && !closed && settings.enabled()) {
                delegate().onStart(parentContext, span);
            }
        }

        @Override
        public boolean isStartRequired() {
            return startRequired;
        }

        @Override
        public synchronized void onEnd(ReadableSpan span) {
            if (endRequired && !closed && settings.enabled()) {
                delegate().onEnd(span);
            }
        }

        @Override
        public boolean isEndRequired() {
            return endRequired;
        }

        private SpanProcessor delegate() {
            if (delegate == null) {
                delegate = factory.get();
            }
            return delegate;
        }

        @Override
        public synchronized CompletableResultCode forceFlush() {
            return delegate == null ? CompletableResultCode.ofSuccess() : delegate.forceFlush();
        }

        @Override
        public synchronized CompletableResultCode shutdown() {
            if (closed) {
                return CompletableResultCode.ofSuccess();
            }
            closed = true;
            return delegate == null ? CompletableResultCode.ofSuccess() : delegate.shutdown();
        }
    }
}
