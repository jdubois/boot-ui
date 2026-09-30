package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import io.github.jdubois.bootui.engine.web.RequestSlowThreshold;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

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
 */
public final class RequestCorrelationFilter extends OncePerRequestFilter {

    private final RequestCorrelationRegistry registry;
    private final HttpExchangeTraceRegistry traceRegistry;
    private final String bootUiPathPrefix;
    private final long requestSlowThresholdMs;

    public RequestCorrelationFilter(
            RequestCorrelationRegistry registry, HttpExchangeTraceRegistry traceRegistry, String bootUiPathPrefix) {
        this(registry, traceRegistry, bootUiPathPrefix, RequestSlowThreshold.DEFAULT_MILLIS);
    }

    /**
     * @param requestSlowThresholdMs {@code bootui.activity.request-slow-threshold-ms}, so a slow request's trace
     *     record is retained as long as its exchange
     */
    public RequestCorrelationFilter(
            RequestCorrelationRegistry registry,
            HttpExchangeTraceRegistry traceRegistry,
            String bootUiPathPrefix,
            long requestSlowThresholdMs) {
        this.registry = registry;
        this.traceRegistry = traceRegistry;
        this.bootUiPathPrefix = bootUiPathPrefix;
        this.requestSlowThresholdMs = requestSlowThresholdMs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
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

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && bootUiPathPrefix != null && uri.startsWith(bootUiPathPrefix);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }
}
