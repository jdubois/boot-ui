package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, in-memory record of the distributed-trace id active when each recent HTTP request completed.
 * Spring MVC feeds it from {@code RequestCorrelationFilter}; WebFlux feeds it from {@code
 * ReactiveHttpExchangeTraceFilter}.
 *
 * <p>Spring Boot's Actuator {@code HttpExchange} model has no trace-id field (see
 * {@code CapturedHttpExchange}'s javadoc), so {@link HttpExchangesController} cannot read one back from
 * the exchange it maps for either stack. This side-buffer supplies the server-created trace id. MVC reads
 * the same SLF4J MDC {@code traceId} used by its SQL/cache/REST capture; WebFlux reads {@code
 * Span.current()}, which survives Reactor Netty event-loop / {@code boundedElastic} hops when automatic
 * context propagation is enabled (see {@code ReactiveOtelTraceIdSource}).</p>
 *
 * <p>An exchange whose BootUI request id is known is matched by that id, exactly ({@code docs/PLAN-v2.md} §5.1).
 * Otherwise, as for an application-provided repository, it is matched by method + path + overlapping time window,
 * exactly like
 * {@code RequestCorrelationRegistry} - including requiring a <em>unique</em> candidate, so two genuinely
 * concurrent identical requests safely correlate neither rather than risk cross-attribution. The buffer
 * is capped so it never grows unbounded. Built by {@link #forExchangeRepository}, it is twice the size of BootUI's
 * HTTP exchange repository and reserves that repository's whole capacity for failed and slow requests, so an
 * exchange the repository keeps longer also keeps its trace id and route template. The two buffers record each
 * request independently, from two filters of the same request, so a retained failure loses its trace record only
 * if more failures than the repository's routine share complete between those two filters; it then reads with no
 * trace id, never a wrong one.</p>
 */
public final class HttpExchangeTraceRegistry {

    /**
     * One completed request: its wall-clock window, method + path, the trace id captured at completion,
     * and the handler pattern that was matched.
     *
     * <p>{@code routeTemplate} is the declared route such as {@code /api/orders/{id}}, or {@code null}
     * when no handler matched. It lets SQL Trace and the HTTP Exchanges route summary group work by route
     * rather than by a path that embeds identifiers, which is the only grouping key that stays
     * low-cardinality and value-free.</p>
     *
     * <p>{@code requestId} is the BootUI request id of the request, or {@code null} when none was open.</p>
     */
    public record HttpExchangeTrace(
            long startMillis,
            long endMillis,
            String method,
            String path,
            String traceId,
            String routeTemplate,
            String requestId) {

        /** The record of a request without a BootUI request id. */
        public HttpExchangeTrace(
                long startMillis, long endMillis, String method, String path, String traceId, String routeTemplate) {
            this(startMillis, endMillis, method, path, traceId, routeTemplate, null);
        }

        /** The trace-only record, for callers with no routing evidence to add. */
        public HttpExchangeTrace(long startMillis, long endMillis, String method, String path, String traceId) {
            this(startMillis, endMillis, method, path, traceId, null, null);
        }
    }

    private final TieredCaptureBuffer<HttpExchangeTrace> buffer;

    /**
     * The registry that indexes a BootUI HTTP exchange repository of {@code maxExchanges}: twice its size, with the
     * repository's whole capacity reserved for failed and slow requests (see the class documentation).
     */
    public static HttpExchangeTraceRegistry forExchangeRepository(int maxExchanges) {
        return new HttpExchangeTraceRegistry((int) Math.min(Integer.MAX_VALUE, 2L * Math.max(1, maxExchanges)), 50);
    }

    /** A registry that evicts strictly oldest first. */
    public HttpExchangeTraceRegistry(int maxEntries) {
        this(maxEntries, 0);
    }

    /**
     * @param maxEntries {@code bootui.http-exchanges.max-exchanges}
     * @param reservedSharePercent {@code bootui.http-exchanges.reserved-share-percent}, so failed and slow requests
     *     are retained as long as their exchanges
     */
    public HttpExchangeTraceRegistry(int maxEntries, int reservedSharePercent) {
        this.buffer = new TieredCaptureBuffer<>(maxEntries, reservedSharePercent);
    }

    /**
     * Records one completed routine request, evicting per the retention policy when the buffer is full.
     * Requests without a usable trace id are retained as ambiguity blockers: otherwise an overlapping traced
     * request with the same method and path could be incorrectly assigned to the untraced exchange.
     */
    public void record(HttpExchangeTrace trace) {
        record(trace, false);
    }

    /**
     * Records one completed request, reserving it with failed and slow requests when {@code failedOrSlow} is set
     * (a {@code 5xx} response, a request that threw, or one at or above the request slow threshold).
     */
    public void record(HttpExchangeTrace trace, boolean failedOrSlow) {
        buffer.add(trace, failedOrSlow);
    }

    /**
     * Returns the trace id captured for the single request whose handling window overlaps
     * {@code [start, end]} for the given method and path, or {@code null} when there is no match or more
     * than one candidate (see {@code RequestCorrelationRegistry#match} for why uniqueness is required).
     */
    public String match(String method, String path, long start, long end) {
        return matcher().match(method, path, start, end);
    }

    /** A matcher over one snapshot of the retained records, for a caller that matches many exchanges at once. */
    public Matcher matcher() {
        return new Matcher(buffer.newestFirst());
    }

    /** Matches exchanges against one snapshot of the retained records. */
    public static final class Matcher {

        private final List<HttpExchangeTrace> traces;

        private final Map<String, HttpExchangeTrace> byRequestId = new HashMap<>();

        private Matcher(List<HttpExchangeTrace> traces) {
            this.traces = traces;
            for (HttpExchangeTrace trace : traces) {
                if (trace.requestId() != null) {
                    byRequestId.put(trace.requestId(), trace);
                }
            }
        }

        /**
         * The record of the request with this BootUI request id, or {@code null} when it has none or its record was
         * evicted. A record found this way is exact: its trace id and route template are the request's own, even
         * when identical requests overlapped it.
         */
        public HttpExchangeTrace byRequestId(String requestId) {
            return requestId == null ? null : byRequestId.get(requestId);
        }

        /** See {@link HttpExchangeTraceRegistry#match}. */
        public String match(String method, String path, long start, long end) {
            if (method == null || path == null) {
                return null;
            }
            long slack = 50L;
            HttpExchangeTrace found = null;
            // Uniqueness, not order, decides the match, so the newest-first snapshot is read as is.
            for (HttpExchangeTrace candidate : traces) {
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
            return found == null || found.traceId() == null || found.traceId().isBlank() ? null : found.traceId();
        }

        /** See {@link HttpExchangeTraceRegistry#matchRouteTemplate}. */
        public String matchRouteTemplate(String method, String path, long start, long end) {
            if (method == null || path == null) {
                return null;
            }
            long slack = 50L;
            String template = null;
            boolean found = false;
            for (HttpExchangeTrace candidate : traces) {
                if (!method.equalsIgnoreCase(candidate.method()) || !path.equals(candidate.path())) {
                    continue;
                }
                if (candidate.startMillis() > end + slack || candidate.endMillis() < start - slack) {
                    continue;
                }
                String candidateTemplate = candidate.routeTemplate() == null
                                || candidate.routeTemplate().isBlank()
                        ? null
                        : candidate.routeTemplate().trim();
                if (candidateTemplate == null) {
                    return null;
                }
                if (found && !candidateTemplate.equals(template)) {
                    return null;
                }
                template = candidateTemplate;
                found = true;
            }
            return template;
        }
    }

    /**
     * Returns the handler pattern recorded for the request(s) whose handling window overlaps
     * {@code [start, end]} for the given method and path, or {@code null} when none matches or the
     * candidates disagree. Unlike {@link #match}, several overlapping candidates are not ambiguous by
     * themselves: requests with the same method and path reach the same handler, so the route is decided
     * whenever every candidate recorded the same, non-blank pattern. A candidate with no pattern, or with a
     * different one, leaves the route undecided rather than guessed.
     */
    public String matchRouteTemplate(String method, String path, long start, long end) {
        return matcher().matchRouteTemplate(method, path, start, end);
    }

    /**
     * A snapshot of the retained records, oldest first. Copied under the lock so a reader — such as the
     * SQL Trace route attribution — never iterates the live buffer while a request is being recorded.
     */
    public List<HttpExchangeTrace> recent() {
        return List.copyOf(buffer.oldestFirst());
    }

    /** Test-only snapshot of the retained records, oldest first. */
    List<HttpExchangeTrace> snapshot() {
        return buffer.oldestFirst();
    }
}
