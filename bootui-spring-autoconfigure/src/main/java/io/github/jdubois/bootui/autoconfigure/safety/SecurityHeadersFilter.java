package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Applies the BootUI response-header security policy to every response on the BootUI surface.
 *
 * <p>This filter is registered at order {@code Integer.MIN_VALUE} — before {@link LocalhostOnlyFilter} and
 * {@link PanelAccessFilter} — so the security headers are present on <em>all</em> BootUI responses
 * including 403 rejections produced by those downstream filters. It sets response headers on the
 * {@link HttpServletResponse} before calling {@code chain.doFilter}, which is sufficient for Servlet
 * containers to include them regardless of what writes the actual body.</p>
 *
 * <p>The header policy is defined in the framework-neutral
 * {@link BootUiSecurityHeaders} engine class, shared by the WebFlux and Quarkus adapters so the three
 * adapters stay at byte-identical parity:</p>
 * <ul>
 *   <li>{@code Content-Security-Policy} — no {@code unsafe-eval}; see {@link BootUiSecurityHeaders#CSP_VALUE}.</li>
 *   <li>{@code X-Content-Type-Options: nosniff}</li>
 *   <li>{@code X-Frame-Options: DENY}</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin}</li>
 *   <li>{@code Cache-Control} — differentiated by path: {@code no-store} for API paths,
 *       {@code immutable} for hashed assets, {@code no-cache} for the shell.</li>
 *   <li>{@code Pragma: no-cache} — paired with {@code no-store} and {@code no-cache} for HTTP/1.0
 *       compatibility (omitted for hashed assets).</li>
 * </ul>
 *
 * <p>Headers set here are intentionally minimal and non-intrusive: they are applied only to
 * {@code /bootui/**} paths, and do not touch any host-application paths or existing host-managed
 * security configuration outside the BootUI surface.</p>
 */
public class SecurityHeadersFilter extends AbstractBootUiFilter {

    public SecurityHeadersFilter(BootUiProperties properties) {
        super(properties);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBootUiRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        applyHeaders(request, response);
        chain.doFilter(request, response);
    }

    private void applyHeaders(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY, BootUiSecurityHeaders.CSP_VALUE);
        response.setHeader(BootUiSecurityHeaders.X_CONTENT_TYPE_OPTIONS, BootUiSecurityHeaders.NOSNIFF);
        response.setHeader(BootUiSecurityHeaders.X_FRAME_OPTIONS, BootUiSecurityHeaders.DENY);
        response.setHeader(
                BootUiSecurityHeaders.REFERRER_POLICY, BootUiSecurityHeaders.STRICT_ORIGIN_WHEN_CROSS_ORIGIN);

        String path = contextRelativePath(request);
        String cacheControl = BootUiSecurityHeaders.cacheControl(path, properties.getApiPath());
        response.setHeader(BootUiSecurityHeaders.CACHE_CONTROL, cacheControl);
        if (!BootUiSecurityHeaders.IMMUTABLE.equals(cacheControl)) {
            response.setHeader(BootUiSecurityHeaders.PRAGMA, BootUiSecurityHeaders.PRAGMA_NO_CACHE);
        }
    }

    private String contextRelativePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }
}
