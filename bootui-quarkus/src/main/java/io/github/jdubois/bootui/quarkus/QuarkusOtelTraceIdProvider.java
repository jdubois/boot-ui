package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * OpenTelemetry-backed {@link TraceIdProvider} for the Quarkus adapter: the reactive-correct seam the Live
 * Activity correlation needs. It produces a {@link TraceIdProvider} that reads the trace id of the active
 * server span via {@link Span#current()}, whose OpenTelemetry context propagates across the Vert.x
 * event-loop→worker-thread hops a request takes. That is why this works where Spring's serving-thread
 * strategy cannot: blocking SQL runs on a worker thread, but the OpenTelemetry context (unlike a
 * thread-local MDC) follows the request onto it, so each capture point reads the <em>same</em> trace id.
 *
 * <p><strong>This is the only OpenTelemetry-importing class added for correlation, and the deployment
 * processor excludes it from bean discovery when OpenTelemetry is absent.</strong> It mirrors
 * {@code BootUiOtelProducer} exactly: the class is deliberately not annotated with a CDI scope, the
 * extension runtime jar is Jandex-indexed (so Arc discovers the always-on beans), and Arc treats a
 * {@code @Produces} method as bean-defining — so this producer would be discovered even in an application
 * without {@code quarkus-opentelemetry}, linking the OpenTelemetry API that must stay absent (R2/BF2). The
 * processor therefore actively excludes this class from discovery unless the OpenTelemetry-tracer capability
 * is present (see {@code BootUiQuarkusProcessor#registerOpenTelemetryCorrelation}). When OpenTelemetry is
 * absent no {@code TraceIdProvider} bean exists, the capture points resolve none and stamp {@code null}, and
 * the Live Activity feed renders flat — the honest status quo.</p>
 */
public class QuarkusOtelTraceIdProvider {

    @Produces
    @Singleton
    public TraceIdProvider bootUiOtelTraceIdProvider() {
        return QuarkusOtelTraceIdProvider::currentSpanTraceId;
    }

    /**
     * The trace id of the span currently in context, or {@code null} when no valid span is active (no
     * request context propagated onto this thread). Fully guarded so a missing or misbehaving tracer never
     * disrupts the work being recorded.
     */
    static String currentSpanTraceId() {
        try {
            SpanContext context = Span.current().getSpanContext();
            return context.isValid() ? context.getTraceId() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
