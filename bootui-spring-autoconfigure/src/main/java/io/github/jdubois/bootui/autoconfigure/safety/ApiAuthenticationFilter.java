package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Authenticates non-loopback requests to the BootUI API.
 *
 * <p>Trust is delegated to {@link LocalhostOnlyFilter#isTrustedSource(String)} rather than
 * re-derived here: a source already trusted via loopback, {@code bootui.trusted-proxies}, or
 * {@code bootui.trust-container-gateway} is treated identically here, so operators who already opted
 * into one of those trust mechanisms keep frictionless access instead of also being forced through the
 * bearer-token/unlock flow.</p>
 */
public final class ApiAuthenticationFilter extends AbstractBootUiFilter {

    public static final String SESSION_PATH = "/auth/session";

    private final ApiTokenAuthenticator authenticator;
    private final LocalhostOnlyFilter localhostOnlyFilter;

    public ApiAuthenticationFilter(
            BootUiProperties properties, ApiTokenAuthenticator authenticator, LocalhostOnlyFilter localhostOnlyFilter) {
        super(properties);
        this.authenticator = authenticator;
        this.localhostOnlyFilter = localhostOnlyFilter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBootUiApiRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean trustedSource = localhostOnlyFilter.isTrustedSource(request.getRemoteAddr());
        boolean authorized = authenticator.isAuthorized(
                trustedSource, request.getHeader("Authorization"), request.getHeader("Cookie"));
        if (!authorized) {
            reject(response);
            return;
        }

        if (isSessionRequest(request)) {
            if (!trustedSource) {
                response.addHeader("Set-Cookie", sessionCookie(request.isSecure()));
            }
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isSessionRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return "POST".equalsIgnoreCase(request.getMethod())
                && path.equals(withoutTrailingSlash(properties.getApiPath()) + SESSION_PATH);
    }

    private String sessionCookie(boolean secure) {
        return ApiTokenAuthenticator.SESSION_COOKIE_NAME
                + "="
                + authenticator.token()
                + "; Path="
                + withoutTrailingSlash(properties.getApiPath())
                + "; HttpOnly; SameSite=Strict"
                + (secure ? "; Secure" : "");
    }

    private static String withoutTrailingSlash(String path) {
        return path != null && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("WWW-Authenticate", ApiTokenAuthenticator.AUTHENTICATION_CHALLENGE);
        response.getWriter().write("{\"error\":\"" + ApiTokenAuthenticator.AUTHENTICATION_REQUIRED_MESSAGE + "\"}");
    }
}
