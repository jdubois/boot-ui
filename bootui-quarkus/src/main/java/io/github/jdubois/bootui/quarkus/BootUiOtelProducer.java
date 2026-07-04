package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.telemetry.BootUiSpanExporter;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * OpenTelemetry capture wiring for the Quarkus adapter: produces the in-process {@link SpanProcessor} that
 * feeds finished spans into the engine {@link TelemetryStore} via the engine {@link BootUiSpanExporter}.
 *
 * <p>This class is the Quarkus analogue of the Spring adapter's {@code BootUiOpenTelemetryConfiguration}.
 * Quarkus OpenTelemetry auto-discovers CDI {@code SpanProcessor} beans and adds them to its
 * {@code TracerProvider}, so wrapping the engine exporter in a {@link SimpleSpanProcessor} is all that is
 * needed to capture spans in-process — no OTLP receiver and zero network, matching
 * {@code quarkus.otel.traces.exporter=none}.</p>
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
            TelemetryStore store, QuarkusTelemetrySettings settings, SelfTelemetryClassifier selfClassifier) {
        // Capture-side classification (BF1) now shares the same instance as every transform/display
        // consumer — produced once in BootUiTelemetryProducer — instead of building its own.
        return SimpleSpanProcessor.create(new BootUiSpanExporter(store, selfClassifier, settings));
    }
}
