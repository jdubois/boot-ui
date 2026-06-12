package io.github.jdubois.bootui.autoconfigure.diagnostics;

import org.slf4j.MDC;

/**
 * Best-effort access to the active trace and span identifiers.
 *
 * <p>Micrometer Tracing (and the OpenTelemetry/Brave bridges) publish the current {@code traceId}
 * and {@code spanId} into the SLF4J {@link MDC} on the request/business thread. BootUI reads them
 * there at capture time so that signals recorded on that thread (SQL executions, exceptions) can be
 * correlated with the originating HTTP request and trace.
 *
 * <p>This is intentionally dependency-free: it only touches SLF4J's {@link MDC}, which is always on
 * the classpath. When no tracing instrumentation is active the MDC keys are absent and these
 * methods simply return {@code null}, so correlation degrades gracefully instead of guessing.
 */
public final class TraceContext {

    /** MDC key Micrometer Tracing uses for the trace identifier. */
    public static final String TRACE_ID_KEY = "traceId";

    /** MDC key Micrometer Tracing uses for the span identifier. */
    public static final String SPAN_ID_KEY = "spanId";

    private TraceContext() {}

    /** Returns the active trace id from the MDC, or {@code null} when no trace context is present. */
    public static String currentTraceId() {
        return normalize(MDC.get(TRACE_ID_KEY));
    }

    /** Returns the active span id from the MDC, or {@code null} when no span context is present. */
    public static String currentSpanId() {
        return normalize(MDC.get(SPAN_ID_KEY));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
