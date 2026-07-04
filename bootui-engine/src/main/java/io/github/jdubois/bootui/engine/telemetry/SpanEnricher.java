package io.github.jdubois.bootui.engine.telemetry;

import java.util.function.BooleanSupplier;

/**
 * Framework-neutral enrichment seam. BootUI's existing capture points (SQL Trace, Exceptions) call this
 * to stamp {@code bootui.*} depth attributes on the currently-active request span as signals accrue.
 *
 * <p>An OpenTelemetry {@code SpanProcessor} cannot mutate a span in {@code onEnd} (it is read-only there),
 * so depth that accrues <em>during</em> a request must be written at the capture point on the active span.
 * This port keeps the engine free of the OpenTelemetry SDK: the default is a {@link #NO_OP no-op}, and each
 * adapter installs the concentrated {@link OtelSpanEnricher} when OpenTelemetry tracing is present. Capture
 * points must never let enrichment disrupt the request, so implementations swallow their own failures.</p>
 */
public interface SpanEnricher {

    /**
     * Whether enrichment is live. Capture points gate any preparatory work (e.g. computing the per-request
     * N+1 suspicion) behind this so the no-op path stays free. The default no-op returns {@code false}.
     */
    default boolean enabled() {
        return false;
    }

    /**
     * Records that one SQL statement was captured under the active request. Implementations increment
     * {@link BootUiSpanAttributes#SQL_QUERIES} on the active span and, once the request is observed to
     * suspect an N+1 pattern, set {@link BootUiSpanAttributes#SQL_N_PLUS_ONE}.
     *
     * <p>The suspicion is supplied lazily so the (potentially O(n)) per-request grouping scan runs only when
     * needed: an implementation that keeps the N+1 flag sticky per span skips the supplier entirely once the
     * span is already flagged.</p>
     */
    default void onSqlStatement(BooleanSupplier nPlusOneSuspected) {}

    /**
     * Records that one exception was captured under the active request. Implementations increment
     * {@link BootUiSpanAttributes#EXCEPTIONS} and set {@link BootUiSpanAttributes#EXCEPTION_TYPE} to the
     * given class name on the active span.
     */
    default void onException(String exceptionType) {}

    /** No-op enricher: the engine default when no adapter has installed an OpenTelemetry-backed one. */
    SpanEnricher NO_OP = new SpanEnricher() {};
}
