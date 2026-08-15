package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records, for every application request, which worker thread served it, its wall-clock window, and the
 * distributed-trace id active at completion. The thread/window record feeds {@link
 * RequestCorrelationRegistry}; the trace record feeds {@link HttpExchangeTraceRegistry}, because
 * Actuator's {@code HttpExchange} model has no trace-id field of its own. Together they let the
 * per-request profiler and Live Flow correlate downstream evidence without relying on an inbound
 * propagation header.
 *
 * <p>It is intentionally a thin wrapper around the filter chain: it reads the current thread name and
 * two timestamps and never touches the request or response, so it cannot alter application behaviour.
 * BootUI's own endpoints are skipped (their requests are hidden from the activity feed anyway), and
 * async/error re-dispatches are skipped so each logical request is recorded exactly once on its main
 * dispatch.</p>
 */
public final class RequestCorrelationFilter extends OncePerRequestFilter {

    private final RequestCorrelationRegistry registry;
    private final HttpExchangeTraceRegistry traceRegistry;
    private final String bootUiPathPrefix;

    public RequestCorrelationFilter(
            RequestCorrelationRegistry registry, HttpExchangeTraceRegistry traceRegistry, String bootUiPathPrefix) {
        this.registry = registry;
        this.traceRegistry = traceRegistry;
        this.bootUiPathPrefix = bootUiPathPrefix;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();
        String method = request.getMethod();
        String path = request.getRequestURI();
        try {
            chain.doFilter(request, response);
        } finally {
            long end = System.currentTimeMillis();
            registry.record(new RequestCorrelation(start, end, thread, method, path));
            traceRegistry.record(new HttpExchangeTrace(start, end, method, decodedPath(path), currentTraceId()));
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
