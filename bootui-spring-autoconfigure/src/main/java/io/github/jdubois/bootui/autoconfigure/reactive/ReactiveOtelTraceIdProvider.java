package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * OpenTelemetry-backed {@link TraceIdProvider} for the Spring WebFlux adapter: the reactive-correct seam
 * the Live Activity correlation needs. It reads the trace id of the active server span via
 * {@link Span#current()}, whose OpenTelemetry context propagates across Reactor Netty's event-loop and
 * {@code boundedElastic} scheduler hops - the same hopping that defeats the servlet adapter's
 * thread-identity correlation ({@code RequestCorrelationRegistry}, {@code SecurityEventCorrelationRegistry}).
 *
 * <p>This mirrors {@code QuarkusOtelTraceIdProvider} exactly, and is installed onto the same capture
 * points (SQL, exceptions, security events, and - unlike Quarkus, which stamps its {@code HttpExchange}
 * DTO directly - the HTTP exchange itself via the side-buffer {@link HttpExchangeTraceRegistry}, since
 * Spring's Actuator {@code HttpExchange} model has no trace-id field to populate). Only wired by {@link
 * io.github.jdubois.bootui.autoconfigure.BootUiReactiveAutoConfiguration}, gated on the OpenTelemetry SDK
 * being present, exactly like {@code BootUiOpenTelemetryConfiguration}; the servlet adapter never
 * registers a bean of this type, so it keeps reading Micrometer's SLF4J MDC {@code traceId} key via
 * {@code SqlTraceRecorder}'s default.</p>
 */
public final class ReactiveOtelTraceIdProvider implements TraceIdProvider {

    @Override
    public String currentTraceId() {
        try {
            SpanContext context = Span.current().getSpanContext();
            return context.isValid() ? context.getTraceId() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
