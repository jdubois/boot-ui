package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral seam for the trace id of the request currently in flight, used to correlate the Live
 * Activity signals (HTTP request, SQL, exception) that share one distributed trace.
 *
 * <p>The engine groups merged activity entries by this id but must never learn <em>how</em> a platform
 * derives it. The Spring adapter's recorder already reads Micrometer's SLF4J MDC {@code traceId} key (the
 * default implementation below preserves that), so it needs no binding. The Quarkus adapter cannot rely on
 * thread identity — its requests hop from the Vert.x event loop to worker threads — so it supplies an
 * OpenTelemetry-backed implementation that reads {@code Span.current()}, whose context propagates across
 * those hops. That OpenTelemetry lookup lives only in the adapter; the engine sees just the resolved
 * {@code String}.</p>
 */
@FunctionalInterface
public interface TraceIdProvider {

    /**
     * The trace id active for the work being recorded, or {@code null}/blank when no trace context is
     * present (no tracer configured, or the calling thread carries no propagated context). Implementations
     * must be fully guarded so a missing or misbehaving tracer never disrupts the work being traced.
     */
    String currentTraceId();
}
