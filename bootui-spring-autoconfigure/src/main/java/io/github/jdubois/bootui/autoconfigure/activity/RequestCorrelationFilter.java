package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records, for every application request, which worker thread served it and the wall-clock window it
 * ran in, feeding {@link RequestCorrelationRegistry}. This is what lets the per-request profiler
 * correlate JDBC statements to a request exactly by serving thread when no trace id is present.
 *
 * <p>It is intentionally a thin wrapper around the filter chain: it reads the current thread name and
 * two timestamps and never touches the request or response, so it cannot alter application behaviour.
 * BootUI's own endpoints are skipped (their requests are hidden from the activity feed anyway), and
 * async/error re-dispatches are skipped so each logical request is recorded exactly once on its main
 * dispatch.</p>
 */
public final class RequestCorrelationFilter extends OncePerRequestFilter {

    private final RequestCorrelationRegistry registry;
    private final String bootUiPathPrefix;

    public RequestCorrelationFilter(RequestCorrelationRegistry registry, String bootUiPathPrefix) {
        this.registry = registry;
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
            registry.record(new RequestCorrelation(start, System.currentTimeMillis(), thread, method, path));
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
