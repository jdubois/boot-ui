package io.github.jdubois.bootui.engine.telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenTelemetry-backed {@link SpanEnricher}: stamps {@code bootui.*} depth attributes on the currently
 * active span ({@link Span#current()}) as SQL statements and exceptions are captured during a request.
 *
 * <p>This is one of the few engine types that touches the OpenTelemetry API; the dependency is optional
 * and a concentration ArchUnit rule pins it. It is installed by each adapter onto the SQL/exception capture
 * points only when OpenTelemetry tracing is present, so an OTel-absent application never links it.</p>
 *
 * <p>A {@code Span} is write-only while recording (its attribute values cannot be read back), so per-request
 * running totals are kept in a small bounded, access-ordered map keyed by span id. {@code setAttribute}
 * overwrites, so writing the running total each time leaves the final (correct) value; the N+1 flag is kept
 * sticky. Enrichment is best-effort: gating is re-read live from {@link TelemetrySettings} and every failure
 * is swallowed so request processing is never disrupted.</p>
 */
public final class OtelSpanEnricher implements SpanEnricher {

    /** Upper bound on tracked in-flight spans; oldest are evicted so memory stays bounded. */
    private static final int MAX_TRACKED_SPANS = 4096;

    private final TelemetrySettings settings;

    private final Map<String, SpanState> states;

    public OtelSpanEnricher(TelemetrySettings settings) {
        this.settings = settings;
        this.states = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SpanState> eldest) {
                return size() > MAX_TRACKED_SPANS;
            }
        };
    }

    @Override
    public boolean enabled() {
        return settings.enabled() && settings.enrichmentEnabled();
    }

    @Override
    public void onSqlStatement(boolean nPlusOneSuspected) {
        if (!enabled()) {
            return;
        }
        try {
            Span span = Span.current();
            SpanContext context = span.getSpanContext();
            if (!context.isValid()) {
                return;
            }
            SpanState state = state(context.getSpanId());
            long queries;
            boolean nPlusOne;
            synchronized (state) {
                state.sqlQueries++;
                state.nPlusOne |= nPlusOneSuspected;
                queries = state.sqlQueries;
                nPlusOne = state.nPlusOne;
            }
            span.setAttribute(BootUiSpanAttributes.SQL_QUERIES, queries);
            if (nPlusOne) {
                span.setAttribute(BootUiSpanAttributes.SQL_N_PLUS_ONE, true);
            }
        } catch (RuntimeException ignored) {
            // Enrichment must never disrupt SQL capture.
        }
    }

    @Override
    public void onException(String exceptionType) {
        if (!enabled()) {
            return;
        }
        try {
            Span span = Span.current();
            SpanContext context = span.getSpanContext();
            if (!context.isValid()) {
                return;
            }
            SpanState state = state(context.getSpanId());
            long exceptions;
            synchronized (state) {
                state.exceptions++;
                exceptions = state.exceptions;
            }
            span.setAttribute(BootUiSpanAttributes.EXCEPTIONS, exceptions);
            if (exceptionType != null && !exceptionType.isBlank()) {
                span.setAttribute(BootUiSpanAttributes.EXCEPTION_TYPE, exceptionType);
            }
        } catch (RuntimeException ignored) {
            // Enrichment must never disrupt exception capture.
        }
    }

    private SpanState state(String spanId) {
        synchronized (states) {
            return states.computeIfAbsent(spanId, id -> new SpanState());
        }
    }

    private static final class SpanState {
        private long sqlQueries;
        private boolean nPlusOne;
        private long exceptions;
    }
}
