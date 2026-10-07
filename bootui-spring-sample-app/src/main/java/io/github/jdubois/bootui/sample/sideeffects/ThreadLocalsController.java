package io.github.jdubois.bootui.sample.sideeffects;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The thread-locals sensor's seeded routes ({@code docs/PLAN-v2.md} §5.16, M5-5f): with the BootUI agent's
 * {@code thread-locals} sensor, {@code GET /api/thread-locals/leak} leaves {@link TenantContext#CURRENT} set on its
 * pooled worker; its counterexamples, {@code cleared} (removed in {@code finally}), {@code nulled} (set to {@code
 * null}), and {@code before} (set by a filter before BootUI's scope opened), never appear; {@code cache} fills a
 * {@code withInitial} date format, reported with its initial value flagged.
 */
@RestController
@RequestMapping("/api/thread-locals")
public class ThreadLocalsController {

    @GetMapping("/leak")
    public Map<String, Object> leak(@RequestParam(name = "tenant", defaultValue = "acme") String tenant) {
        TenantContext.CURRENT.set("tenant-secret-" + tenant);
        return Map.of("virtual", TenantContext.virtual());
    }

    @GetMapping("/cleared")
    public Map<String, Object> cleared(@RequestParam(name = "tenant", defaultValue = "acme") String tenant) {
        TenantContext.CURRENT.set("tenant-secret-" + tenant);
        try {
            return Map.of("virtual", TenantContext.virtual());
        } finally {
            TenantContext.CURRENT.remove();
        }
    }

    @GetMapping("/nulled")
    public Map<String, Object> nulled() {
        TenantContext.CURRENT.set("tenant-secret-nulled");
        TenantContext.CURRENT.set(null);
        return Map.of("virtual", TenantContext.virtual());
    }

    @GetMapping("/cache")
    public Map<String, Object> cache() {
        return Map.of("day", TenantContext.FORMAT.get().format(new Date(0L)), "virtual", TenantContext.virtual());
    }

    @GetMapping("/before")
    public Map<String, Object> before() {
        return Map.of("virtual", TenantContext.virtual());
    }

    /** Sets {@link TenantContext#BEFORE} ahead of BootUI's request scope, and never clears it. */
    @Bean
    static FilterRegistrationBean<Filter> threadLocalBeforeFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                if (request instanceof HttpServletRequest http
                        && http.getRequestURI().endsWith("/thread-locals/before")) {
                    TenantContext.BEFORE.set("tenant-secret-before");
                }
                chain.doFilter(request, response);
            }
        });
        registration.addUrlPatterns("/api/thread-locals/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
