package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import io.github.jdubois.bootui.autoconfigure.web.BootUiMounts;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.javaagent.AgentCodePaths;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventSink;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.engine.resources.SegmentMeter;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import io.github.jdubois.bootui.spi.CorrelationContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * Records, for every application request, which worker thread served it, its wall-clock window, and the
 * distributed-trace id active at completion. The thread/window record feeds {@link
 * RequestCorrelationRegistry}; the trace record feeds {@link HttpExchangeTraceRegistry}, because
 * Actuator's {@code HttpExchange} model has no trace-id field of its own. Together they let the
 * per-request profiler and Live Flow correlate downstream evidence without relying on an inbound
 * propagation header.
 *
 * <p>It is intentionally a thin wrapper around the filter chain: it reads the current thread name, two
 * timestamps and two request attributes, and never mutates the request or response, so it cannot alter
 * application behaviour.
 * BootUI's own endpoints are skipped (their requests are hidden from the activity feed anyway), and
 * async/error re-dispatches are skipped so each logical request is recorded exactly once on its main
 * dispatch.</p>
 *
 * <p>It also gives each request BootUI's own request id ({@code docs/PLAN-v2.md} §5.1) and makes its
 * {@link CorrelationContext} current on the serving thread while the chain runs, so every event recorded for the
 * request, including the exchange Actuator records on the same thread, carries that id with or without tracing. The
 * context is kept as a request attribute and made current again on an async redispatch, which may run on another
 * thread, and on the container's error dispatch, such as {@code /error}; neither is recorded again. A request the
 * error dispatch serves on its own, without a first dispatch through this filter, stays unowned.</p>
 */
public final class RequestCorrelationFilter extends OncePerRequestFilter {

    private final RequestCorrelationRegistry registry;
    private final HttpExchangeTraceRegistry traceRegistry;
    private final String bootUiPath;
    private final String bootUiApiPath;
    private final long requestSlowThresholdMs;
    private final RequestPhases phases;

    public RequestCorrelationFilter(
            RequestCorrelationRegistry registry, HttpExchangeTraceRegistry traceRegistry, String bootUiPath) {
        this(registry, traceRegistry, bootUiPath, null, RequestSlowThreshold.DEFAULT_MILLIS);
    }

    /**
     * @param bootUiPath {@code bootui.path}
     * @param bootUiApiPath {@code bootui.api-path}
     * @param requestSlowThresholdMs {@code bootui.activity.request-slow-threshold-ms}, so a slow request's trace
     *     record is retained as long as its exchange
     */
    public RequestCorrelationFilter(
            RequestCorrelationRegistry registry,
            HttpExchangeTraceRegistry traceRegistry,
            String bootUiPath,
            String bootUiApiPath,
            long requestSlowThresholdMs) {
        this(registry, traceRegistry, bootUiPath, bootUiApiPath, requestSlowThresholdMs, null);
    }

    /**
     * @param phases the phase markers of recent requests, begun here for each request; {@code null} tracks none
     */
    public RequestCorrelationFilter(
            RequestCorrelationRegistry registry,
            HttpExchangeTraceRegistry traceRegistry,
            String bootUiPath,
            String bootUiApiPath,
            long requestSlowThresholdMs,
            RequestPhases phases) {
        this.phases = phases;
        this.registry = registry;
        this.traceRegistry = traceRegistry;
        this.bootUiPath = bootUiPath;
        this.bootUiApiPath = bootUiApiPath;
        this.requestSlowThresholdMs = requestSlowThresholdMs;
    }

    private volatile RuntimeEventSink journal = RuntimeEventSink.NONE;

    /**
     * Installs the runtime journal ({@code docs/PLAN-v2.md} §5.2), which receives one {@code HTTP} event per request
     * when the request completes. {@code null} restores the default, which publishes nothing.
     */
    public void setRuntimeEventSink(RuntimeEventSink journal) {
        this.journal = journal == null ? RuntimeEventSink.NONE : journal;
    }

    /** The request attribute holding the request's {@link CorrelationContext}, for its async redispatches. */
    public static final String CORRELATION_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".correlation";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAsyncDispatch(request) || request.getDispatcherType() == DispatcherType.ERROR) {
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(asyncContext(request))) {
                chain.doFilter(request, response);
            }
            return;
        }
        if (isBootUiRequest(request)) {
            // BootUI's own request: never recorded, and the work it does, such as a panel's SQL, stays out of the
            // runtime journal (docs/PLAN-v2.md §5.2).
            request.setAttribute(CORRELATION_ATTRIBUTE, CorrelationContext.BOOTUI);
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
                chain.doFilter(request, response);
            }
            return;
        }
        CorrelationContext correlation = CorrelationContext.forRequest(RequestIds.next());
        request.setAttribute(CORRELATION_ATTRIBUTE, correlation);
        if (phases != null) {
            phases.begin(correlation.requestId());
        }
        if (journal.records(JournalSource.RESOURCES)) {
            SegmentMeter.shared().begin(correlation.requestId());
        }
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(correlation)) {
            // The BootUI agent's code-paths fragment of this request on this thread (docs/PLAN-v2.md M5-4a): opened
            // inside the scope, so it captures the request, and flushed before the scope closes.
            AgentCodePaths.begin();
            AgentCodePaths.phase(RequestPhase.FILTERS);
            try {
                recordAround(request, response, chain, correlation);
            } finally {
                try {
                    markAssemblyOnlyIfAsync(request, correlation);
                    endRequestValues(request, correlation);
                } finally {
                    AgentCodePaths.end();
                }
            }
        }
    }

    /**
     * Marks the request's code-paths tree assembly only when its handler only started async processing (a
     * {@code Callable}, {@code DeferredResult}, {@code CompletableFuture}, or reactive type): its tree times that, not
     * the work, which finishes on another thread ({@code docs/PLAN-v2.md} §5.14, M5-4b). Never throws, so neither the
     * fragment's end nor the chain's own exception is lost: a request the container already recycled answers
     * {@code isAsyncStarted()} with an exception.
     */
    static void markAssemblyOnlyIfAsync(HttpServletRequest request, CorrelationContext correlation) {
        try {
            if (AgentCodePaths.bound() && request.isAsyncStarted()) {
                AgentCodePaths.assemblyOnly(correlation.requestId());
            }
        } catch (RuntimeException ex) {
            // Code paths are diagnostics only; the request's outcome stays its own.
        }
    }

    /**
     * Removes the request's values from the BootUI agent's request value holder where its response really completes
     * ({@code docs/PLAN-v2.md} §5.16, M5-6b): here for a synchronous request, and when the async cycle completes, fails,
     * or times out for one that started async processing, so no value outlives the response. Only while request-value
     * matching is configured. Never throws, so neither the fragment's end nor the chain's own exception is lost.
     */
    static void endRequestValues(HttpServletRequest request, CorrelationContext correlation) {
        if (!AgentRequestValues.enabled()) {
            return;
        }
        String requestId = correlation.requestId();
        try {
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new RequestValuesEnd(requestId));
                return;
            }
        } catch (RuntimeException ex) {
            // A request the container already recycled, or an async cycle that already ended: end the values now.
        }
        AgentRequestValues.end(requestId);
    }

    /** Ends a request's values once its async cycle is over. */
    private record RequestValuesEnd(String requestId) implements AsyncListener {

        @Override
        public void onComplete(AsyncEvent event) {
            AgentRequestValues.end(requestId);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            AgentRequestValues.end(requestId);
        }

        @Override
        public void onError(AsyncEvent event) {
            AgentRequestValues.end(requestId);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // A new cycle of the same request: the values stay until it ends, with this listener registered again.
            event.getAsyncContext().addListener(this);
        }
    }

    private static CorrelationContext asyncContext(HttpServletRequest request) {
        Object attribute = request.getAttribute(CORRELATION_ATTRIBUTE);
        return attribute instanceof CorrelationContext context ? context : CorrelationContext.NONE;
    }

    private void recordAround(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain, CorrelationContext correlation)
            throws ServletException, IOException {
        String requestId = correlation.requestId();
        long startNanos = System.nanoTime();
        long start = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean threw = true;
        try {
            chain.doFilter(request, response);
            threw = false;
        } finally {
            long end = System.currentTimeMillis();
            String traceId = currentTraceId();
            String routeTemplate = routeTemplate(request);
            registry.record(new RequestCorrelation(start, end, thread, method, path, routeTemplate, traceId));
            // Classified exactly as Actuator's servlet HttpExchangesFilter records the exchange: 500 whenever the
            // chain throws, so the trace record and the exchange agree on whether it is reserved.
            int status = threw ? 500 : response.getStatus();
            boolean failedOrSlow = RequestSlowThreshold.isFailedOrSlow(status, end - start, requestSlowThresholdMs);
            String decodedPath = decodedPath(path);
            // Ends the request's measurement (docs/PLAN-v2.md §5.11): an async request's later dispatches are not
            // counted, as its HTTP event, published here, does not time them either.
            ResourceUsage resources = SegmentMeter.shared().take(requestId);
            traceRegistry.record(
                    new HttpExchangeTrace(start, end, method, decodedPath, traceId, routeTemplate, requestId),
                    failedOrSlow);
            // An async request answers on a later dispatch, so its handler is still running: work it handed over and
            // that ends before that dispatch writes the response did not run after it.
            if (phases != null && !request.isAsyncStarted()) {
                phases.end(requestId);
            }
            try {
                journal.offer(RuntimeEvent.of(
                        JournalSource.HTTP,
                        start,
                        System.nanoTime() - startNanos,
                        correlation,
                        traceId,
                        thread,
                        null,
                        failedOrSlow,
                        new HttpPayload(
                                method,
                                decodedPath,
                                routeTemplate,
                                phases == null ? null : phases.operationOf(requestId),
                                status,
                                resources,
                                RequestTiming.of(startNanos, phases == null ? null : phases.markers(requestId)))));
            } catch (RuntimeException ex) {
                // Publishing never disturbs the request it observes.
            }
        }
    }

    private String decodedPath(String path) {
        if (path == null) {
            return null;
        }
        try {
            return URI.create(path).getPath();
        } catch (IllegalArgumentException ex) {
            return path;
        }
    }

    /**
     * The handler pattern Spring MVC matched for this request, such as {@code /api/orders/{id}}, read from
     * the attribute the handler mapping publishes. Returns {@code null} when no handler matched (a 404, a
     * static resource, a request rejected before routing) — a route BootUI cannot name is reported as
     * unknown rather than guessed at from the raw path. Fully guarded: this filter must never alter the
     * outcome of a request.
     */
    private String routeTemplate(HttpServletRequest request) {
        try {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern == null) {
                return null;
            }
            String template = pattern.toString().trim();
            return template.isEmpty() ? null : template;
        } catch (RuntimeException | NoClassDefFoundError ex) {
            return null;
        }
    }

    private String currentTraceId() {
        try {
            return MDC.get("traceId");
        } catch (RuntimeException | NoClassDefFoundError ex) {
            return null;
        }
    }

    /**
     * Skips BootUI's own requests, matched on the decoded path below the context path exactly as BootUI's recording
     * filter matches them, so, while {@code bootui.monitoring.exclude-self} is on, this filter's trace records and the
     * recorded exchanges cover the same requests. With it off, BootUI's own exchanges are recorded and shown without a
     * server trace id or route template, as before.
     */
    private boolean isBootUiRequest(HttpServletRequest request) {
        return BootUiMounts.contains(
                UrlPathHelper.defaultInstance.getPathWithinApplication(request), bootUiPath, bootUiApiPath);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
