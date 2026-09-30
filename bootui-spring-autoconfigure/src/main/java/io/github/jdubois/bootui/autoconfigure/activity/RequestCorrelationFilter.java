package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import io.github.jdubois.bootui.autoconfigure.web.BootUiMounts;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestIds;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import io.github.jdubois.bootui.spi.CorrelationContext;
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
 * thread; the redispatch is not recorded again.</p>
 */
public final class RequestCorrelationFilter extends OncePerRequestFilter {

    private final RequestCorrelationRegistry registry;
    private final HttpExchangeTraceRegistry traceRegistry;
    private final String bootUiPath;
    private final String bootUiApiPath;
    private final long requestSlowThresholdMs;

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
        this.registry = registry;
        this.traceRegistry = traceRegistry;
        this.bootUiPath = bootUiPath;
        this.bootUiApiPath = bootUiApiPath;
        this.requestSlowThresholdMs = requestSlowThresholdMs;
    }

    /** The request attribute holding the request's {@link CorrelationContext}, for its async redispatches. */
    public static final String CORRELATION_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".correlation";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAsyncDispatch(request)) {
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(asyncContext(request))) {
                chain.doFilter(request, response);
            }
            return;
        }
        CorrelationContext correlation = CorrelationContext.forRequest(RequestIds.next());
        request.setAttribute(CORRELATION_ATTRIBUTE, correlation);
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(correlation)) {
            recordAround(request, response, chain);
        }
    }

    private static CorrelationContext asyncContext(HttpServletRequest request) {
        Object attribute = request.getAttribute(CORRELATION_ATTRIBUTE);
        return attribute instanceof CorrelationContext context ? context : CorrelationContext.NONE;
    }

    private void recordAround(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
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
            traceRegistry.record(
                    new HttpExchangeTrace(start, end, method, decodedPath(path), traceId, routeTemplate),
                    RequestSlowThreshold.isFailedOrSlow(status, end - start, requestSlowThresholdMs));
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
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return BootUiMounts.contains(
                UrlPathHelper.defaultInstance.getPathWithinApplication(request), bootUiPath, bootUiApiPath);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }
}
