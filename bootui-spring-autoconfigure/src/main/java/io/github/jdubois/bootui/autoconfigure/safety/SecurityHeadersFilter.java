package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

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
        String path = contextRelativePath(request);
        SecurityHeadersResponse wrappedResponse = new SecurityHeadersResponse(response, path, properties.getApiPath());
        try {
            chain.doFilter(request, wrappedResponse);
        } catch (IOException | ServletException | RuntimeException | Error exception) {
            wrappedResponse.applyPolicy(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            throw exception;
        }
        wrappedResponse.applyPolicy();
    }

    private String contextRelativePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }

    /**
     * Installs BootUI's baseline immediately for streaming responses while allowing downstream host
     * security writers to replace it without creating duplicate values. Cache headers remain locked to
     * BootUI's response-class policy.
     */
    private static final class SecurityHeadersResponse extends HttpServletResponseWrapper {

        private final String path;
        private final String apiPath;
        private final Set<String> hostHeaders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        private java.util.Map<String, String> policy;
        private boolean initialized;

        private SecurityHeadersResponse(HttpServletResponse response, String path, String apiPath) {
            super(response);
            this.path = path;
            this.apiPath = apiPath;
            applyPolicy();
        }

        @Override
        public boolean containsHeader(String name) {
            if (isBaselineSecurityHeader(name) && !hostHeaders.contains(name)) {
                return false;
            }
            return super.containsHeader(name);
        }

        @Override
        public void setHeader(String name, String value) {
            if (BootUiSecurityHeaders.overridesExisting(name)) {
                return;
            }
            markHostHeader(name);
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            if (BootUiSecurityHeaders.overridesExisting(name)) {
                return;
            }
            if (isBaselineSecurityHeader(name) && !hostHeaders.contains(name)) {
                hostHeaders.add(name);
                super.setHeader(name, value);
                return;
            }
            markHostHeader(name);
            super.addHeader(name, value);
        }

        @Override
        public void reset() {
            super.reset();
            hostHeaders.clear();
            initialized = false;
            applyPolicy();
        }

        @Override
        public void setStatus(int statusCode) {
            super.setStatus(statusCode);
            applyPolicy();
        }

        @Override
        public void sendError(int statusCode) throws IOException {
            super.setStatus(statusCode);
            applyPolicy();
            super.sendError(statusCode);
        }

        @Override
        public void sendError(int statusCode, String message) throws IOException {
            super.setStatus(statusCode);
            applyPolicy();
            super.sendError(statusCode, message);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            super.setStatus(SC_FOUND);
            applyPolicy();
            super.sendRedirect(location);
        }

        private void applyPolicy() {
            applyPolicy(getStatus());
        }

        private void applyPolicy(int statusCode) {
            policy = BootUiSecurityHeaders.headersFor(path, apiPath, statusCode);
            if (BootUiSecurityHeaders.removesPragma(path, apiPath, statusCode)) {
                super.setHeader(BootUiSecurityHeaders.PRAGMA, null);
            }
            policy.forEach((name, value) -> {
                if (BootUiSecurityHeaders.overridesExisting(name)) {
                    super.setHeader(name, value);
                } else if (hostHeaders.contains(name)) {
                    // A downstream host writer owns this security header.
                } else if (super.containsHeader(name) && (!initialized || !value.equals(super.getHeader(name)))) {
                    hostHeaders.add(name);
                } else if (!super.containsHeader(name)) {
                    super.setHeader(name, value);
                }
            });
            initialized = true;
        }

        private boolean isBaselineSecurityHeader(String name) {
            return policy.keySet().stream()
                    .anyMatch(policyName ->
                            !BootUiSecurityHeaders.overridesExisting(policyName) && policyName.equalsIgnoreCase(name));
        }

        private void markHostHeader(String name) {
            if (isBaselineSecurityHeader(name)) {
                hostHeaders.add(name);
            }
        }
    }
}
