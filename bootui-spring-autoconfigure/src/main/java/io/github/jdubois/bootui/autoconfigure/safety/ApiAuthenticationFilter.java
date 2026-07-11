package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Authenticates non-loopback requests to the BootUI API. */
public final class ApiAuthenticationFilter extends AbstractBootUiFilter {

    public static final String SESSION_PATH = "/auth/session";

    private final ApiTokenAuthenticator authenticator;

    public ApiAuthenticationFilter(BootUiProperties properties, ApiTokenAuthenticator authenticator) {
        super(properties);
        this.authenticator = authenticator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBootUiApiRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean authorized = authenticator.isAuthorized(
                request.getRemoteAddr(), request.getHeader("Authorization"), request.getHeader("Cookie"));
        if (!authorized) {
            reject(response);
            return;
        }

        if (isSessionRequest(request)) {
            if (!authenticator.isLoopback(request.getRemoteAddr())) {
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
