package io.github.jdubois.bootui.autoconfigure.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, in-memory record of the distributed-trace id active when each recent HTTP request completed on
 * the reactive (WebFlux) adapter, captured by {@code ReactiveHttpExchangeTraceFilter}.
 *
 * <p>Spring Boot's Actuator {@code HttpExchange} model has no trace-id field (see
 * {@code CapturedHttpExchange}'s javadoc), so {@link HttpExchangesController} cannot read one back from
 * the exchange it maps for either stack. This side-buffer supplies it for WebFlux, where
 * {@code Span.current()} - not thread identity - is the only signal that survives the Reactor Netty
 * event-loop / {@code boundedElastic} hop a request may take (see {@code ReactiveOtelTraceIdProvider}).
 * The servlet adapter never registers a bean of this type, so {@link HttpExchangesController#toCaptured}
 * simply finds none and keeps its existing header-derived behavior unchanged.</p>
 *
 * <p>Matched by method + path + overlapping time window, exactly like
 * {@code RequestCorrelationRegistry} - including requiring a <em>unique</em> candidate, so two genuinely
 * concurrent identical requests safely correlate neither rather than risk cross-attribution. The buffer
 * is capped and evicts oldest-first so it never grows unbounded.</p>
 */
public final class HttpExchangeTraceRegistry {

    /** One completed request: its wall-clock window, method + path, and the trace id captured at completion. */
    public record HttpExchangeTrace(long startMillis, long endMillis, String method, String path, String traceId) {}

    private final int maxEntries;
    private final Deque<HttpExchangeTrace> buffer = new ArrayDeque<>();
    private final Object lock = new Object();

    public HttpExchangeTraceRegistry(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * Records one completed request's captured trace id, evicting the oldest entry when the buffer is
     * full. A blank/missing trace id is not recorded at all - there is nothing useful to correlate later.
     */
    public void record(HttpExchangeTrace trace) {
        if (trace == null || trace.traceId() == null || trace.traceId().isBlank()) {
            return;
        }
        synchronized (lock) {
            buffer.addLast(trace);
            while (buffer.size() > maxEntries) {
                buffer.removeFirst();
            }
        }
    }

    /**
     * Returns the trace id captured for the single request whose handling window overlaps
     * {@code [start, end]} for the given method and path, or {@code null} when there is no match or more
     * than one candidate (see {@code RequestCorrelationRegistry#match} for why uniqueness is required).
     */
    public String match(String method, String path, long start, long end) {
        if (method == null || path == null) {
            return null;
        }
        long slack = 50L;
        HttpExchangeTrace found = null;
        synchronized (lock) {
            for (HttpExchangeTrace candidate : buffer) {
                if (!method.equalsIgnoreCase(candidate.method()) || !path.equals(candidate.path())) {
                    continue;
                }
                if (candidate.startMillis() > end + slack || candidate.endMillis() < start - slack) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = candidate;
            }
        }
        return found == null ? null : found.traceId();
    }

    /** Test-only snapshot of the retained records, oldest first. */
    List<HttpExchangeTrace> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(buffer);
        }
    }
}
