package io.github.jdubois.bootui.engine.correlation;

/**
 * An adapter's bridge to its tracer, read only by {@link ScopedCorrelationContextProvider} to fill in the trace id of
 * the work being recorded when its correlation scope has none ({@code docs/PLAN-v2.md} §5.1). It is internal, not a
 * service-provider interface: recorders and capture points read trace ids from the adapter's
 * {@code CorrelationContextProvider}, which replaced 1.x's {@code TraceIdProvider} in 2.0.0.
 *
 * <p>Spring WebFlux and Quarkus implement it with OpenTelemetry's {@code Span.current()}, whose context follows a
 * request across Reactor and Vert.x thread hops. Implementations must never block and must be fully guarded.</p>
 */
@FunctionalInterface
public interface TraceIdSource {

    /** The trace id active for the work being recorded, or {@code null} or blank when no trace context is present. */
    String currentTraceId();
}
