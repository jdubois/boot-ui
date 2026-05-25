package io.github.bootui.autoconfigure.web;

import io.github.bootui.autoconfigure.BootUiProperties;
import io.github.bootui.core.BootUiDtos.SecurityAuthDto;
import io.github.bootui.core.BootUiDtos.SecurityEndpointDto;
import io.github.bootui.core.BootUiDtos.SecurityEndpointsReport;
import io.github.bootui.core.BootUiDtos.SecurityExplainDto;
import io.github.bootui.core.BootUiDtos.SecurityFilterChainDto;
import io.github.bootui.core.BootUiDtos.SecurityReport;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.MappingMatch;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Spring Security filter chain configuration for the BootUI developer console.
 *
 * <p>Read-only. Never surfaces credentials, signing keys, or session identifiers.
 * Activated only when {@code spring-security-web} is on the classpath.</p>
 */
@RestController
@ConditionalOnClass(FilterChainProxy.class)
@RequestMapping("/bootui/api/security")
public class SecurityController {

    private final ObjectProvider<FilterChainProxy> filterChainProxyProvider;
    private final ObjectProvider<AuthenticationProvider> authenticationProviderProvider;
    private final ObjectProvider<UserDetailsService> userDetailsServiceProvider;
    private final Environment environment;
    private final BootUiProperties properties;

    public SecurityController(ObjectProvider<FilterChainProxy> filterChainProxyProvider,
                              ObjectProvider<AuthenticationProvider> authenticationProviderProvider,
                              ObjectProvider<UserDetailsService> userDetailsServiceProvider,
                              Environment environment,
                              BootUiProperties properties) {
        this.filterChainProxyProvider = filterChainProxyProvider;
        this.authenticationProviderProvider = authenticationProviderProvider;
        this.userDetailsServiceProvider = userDetailsServiceProvider;
        this.environment = environment;
        this.properties = properties;
    }

    @GetMapping
    public SecurityReport security() {
        FilterChainProxy proxy = filterChainProxyProvider.getIfAvailable();
        if (proxy == null) {
            return new SecurityReport(false, List.of(), null);
        }
        List<SecurityFilterChain> chains = proxy.getFilterChains();
        List<SecurityFilterChainDto> chainDtos = new ArrayList<>(chains.size());
        for (int i = 0; i < chains.size(); i++) {
            chainDtos.add(toChainDto(i, chains.get(i)));
        }
        return new SecurityReport(true, chainDtos, buildAuth());
    }

    /**
     * Best-effort explain: given an HTTP method and path, returns the first matching chain
     * and its filter pipeline.
     *
     * <p>Matching is performed with a minimal request stub that covers method and path only.
     * Header- or session-based matchers may not match correctly; {@code bestEffort} is
     * {@code true} when the stub detected that such matchers were consulted.</p>
     */
    @GetMapping("/explain")
    public SecurityExplainDto explain(@RequestParam(defaultValue = "GET") String method,
                                      @RequestParam(defaultValue = "/") String path) {
        FilterChainProxy proxy = filterChainProxyProvider.getIfAvailable();
        if (proxy == null) {
            return new SecurityExplainDto(false, false, null, null, List.of());
        }
        ExplainRequest request = new ExplainRequest(method, path);
        List<SecurityFilterChain> chains = proxy.getFilterChains();
        for (int i = 0; i < chains.size(); i++) {
            SecurityFilterChain chain = chains.get(i);
            boolean matches;
            try {
                matches = chain.matches(request);
            } catch (Exception ex) {
                return new SecurityExplainDto(false, true, null,
                        "Chain " + i + " matcher threw " + ex.getClass().getSimpleName()
                                + " — requires more request context than available",
                        List.of());
            }
            if (matches) {
                return new SecurityExplainDto(
                        true,
                        request.isBestEffort(),
                        i,
                        matcherDescription(chain),
                        filterNames(chain.getFilters()));
            }
        }
        return new SecurityExplainDto(false, request.isBestEffort(), null, "No chain matched", List.of());
    }

    private SecurityFilterChainDto toChainDto(int order, SecurityFilterChain chain) {
        return new SecurityFilterChainDto(
                order,
                matcherDescription(chain),
                matcherTypeName(chain),
                filterNames(chain.getFilters()),
                hasFilter(chain, "CsrfFilter"),
                hasFilter(chain, "CorsFilter"),
                hasFilter(chain, "SessionManagementFilter"));
    }

    private String matcherDescription(SecurityFilterChain chain) {
        if (chain instanceof DefaultSecurityFilterChain dfc) {
            return dfc.getRequestMatcher().toString();
        }
        return "(custom chain: " + chain.getClass().getSimpleName() + ")";
    }

    private String matcherTypeName(SecurityFilterChain chain) {
        if (chain instanceof DefaultSecurityFilterChain dfc) {
            return dfc.getRequestMatcher().getClass().getSimpleName();
        }
        return chain.getClass().getSimpleName();
    }

    private List<String> filterNames(List<? extends jakarta.servlet.Filter> filters) {
        return filters.stream()
                .map(f -> f.getClass().getSimpleName())
                .collect(Collectors.toList());
    }

    private boolean hasFilter(SecurityFilterChain chain, String simpleClassName) {
        return chain.getFilters().stream()
                .anyMatch(f -> f.getClass().getSimpleName().equals(simpleClassName));
    }

    private SecurityAuthDto buildAuth() {
        List<String> providerTypes = authenticationProviderProvider.stream()
                .map(p -> p.getClass().getName())
                .sorted()
                .collect(Collectors.toList());
        List<String> udsTypes = userDetailsServiceProvider.stream()
                .map(u -> u.getClass().getName())
                .sorted()
                .collect(Collectors.toList());
        // spring.security.user.name is a username, not a secret; expose it to help
        // developers identify the auto-generated user when no custom UserDetailsService
        // is configured. Never read spring.security.user.password.
        String configuredUsername = null;
        if (properties.getExposeValues() != BootUiProperties.ValueExposure.METADATA_ONLY) {
            configuredUsername = environment.getProperty("spring.security.user.name");
        }
        return new SecurityAuthDto(providerTypes, udsTypes, configuredUsername);
    }

    /**
     * Minimal {@link HttpServletRequest} stub used for best-effort chain matching in the
     * explain endpoint. Provides method, path, and common safe defaults. Any method call
     * that indicates header- or session-based matching sets {@link #isBestEffort()} so the
     * caller can communicate reduced confidence to the client.
     */
    private static final class ExplainRequest implements HttpServletRequest {

        private final String method;
        private final String path;
        private boolean bestEffort;

        ExplainRequest(String method, String path) {
            this.method = method != null ? method.toUpperCase(Locale.ROOT) : "GET";
            this.path = path != null ? (path.startsWith("/") ? path : "/" + path) : "/";
        }

        boolean isBestEffort() {
            return bestEffort;
        }

        // ── Core path/method ──────────────────────────────────────────────────────

        @Override public String getMethod() { return method; }
        @Override public String getServletPath() { return path; }
        @Override public String getRequestURI() { return path; }
        @Override public StringBuffer getRequestURL() { return new StringBuffer("http://localhost" + path); }
        @Override public String getContextPath() { return ""; }
        @Override public String getPathInfo() { return null; }
        @Override public String getPathTranslated() { return null; }
        @Override public String getQueryString() { return null; }

        // ── Remote / connection ───────────────────────────────────────────────────

        @Override public String getRemoteAddr() { return "127.0.0.1"; }
        @Override public String getRemoteHost() { return "localhost"; }
        @Override public int getRemotePort() { return 0; }
        @Override public String getLocalAddr() { return "127.0.0.1"; }
        @Override public String getLocalName() { return "localhost"; }
        @Override public int getLocalPort() { return 80; }
        @Override public String getScheme() { return "http"; }
        @Override public String getServerName() { return "localhost"; }
        @Override public int getServerPort() { return 80; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public boolean isSecure() { return false; }

        // ── Headers — mark best-effort since header matchers may produce wrong results ──

        @Override
        public String getHeader(String name) {
            bestEffort = true;
            return null;
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            bestEffort = true;
            return Collections.emptyEnumeration();
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            bestEffort = true;
            return Collections.emptyEnumeration();
        }

        @Override public long getDateHeader(String name) { bestEffort = true; return -1L; }
        @Override public int getIntHeader(String name) { bestEffort = true; return -1; }

        // ── Session / auth — mark best-effort ─────────────────────────────────────

        @Override public HttpSession getSession(boolean create) { bestEffort = true; return null; }
        @Override public HttpSession getSession() { bestEffort = true; return null; }
        @Override public String getRequestedSessionId() { return null; }
        @Override public boolean isRequestedSessionIdValid() { return false; }
        @Override public boolean isRequestedSessionIdFromCookie() { return false; }
        @Override public boolean isRequestedSessionIdFromURL() { return false; }
        @Override public Principal getUserPrincipal() { return null; }
        @Override public String getRemoteUser() { return null; }
        @Override public boolean isUserInRole(String role) { return false; }
        @Override public String getAuthType() { return null; }
        @Override public Cookie[] getCookies() { return null; }

        // ── Attributes ────────────────────────────────────────────────────────────

        @Override public Object getAttribute(String name) { return null; }
        @Override public Enumeration<String> getAttributeNames() { return Collections.emptyEnumeration(); }
        @Override public void setAttribute(String name, Object o) { /* no-op */ }
        @Override public void removeAttribute(String name) { /* no-op */ }

        // ── Parameters ────────────────────────────────────────────────────────────

        @Override public String getParameter(String name) { return null; }
        @Override public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
        @Override public String[] getParameterValues(String name) { return null; }
        @Override public Map<String, String[]> getParameterMap() { return Map.of(); }

        // ── Content / encoding ────────────────────────────────────────────────────

        @Override public String getCharacterEncoding() { return "UTF-8"; }
        @Override public void setCharacterEncoding(String env) { /* no-op */ }
        @Override public int getContentLength() { return -1; }
        @Override public long getContentLengthLong() { return -1L; }
        @Override public String getContentType() { return null; }
        @Override public ServletInputStream getInputStream() { throw new UnsupportedOperationException(); }
        @Override public BufferedReader getReader() { throw new UnsupportedOperationException(); }

        // ── Locale ────────────────────────────────────────────────────────────────

        @Override public Locale getLocale() { return Locale.getDefault(); }
        @Override public Enumeration<Locale> getLocales() { return Collections.enumeration(List.of(Locale.getDefault())); }

        // ── Dispatch ──────────────────────────────────────────────────────────────

        @Override public DispatcherType getDispatcherType() { return DispatcherType.REQUEST; }
        @Override public RequestDispatcher getRequestDispatcher(String path) { return null; }
        @Override public ServletContext getServletContext() { return null; }
        @Override public boolean isAsyncSupported() { return false; }
        @Override public boolean isAsyncStarted() { return false; }
        @Override public AsyncContext getAsyncContext() { return null; }
        @Override public AsyncContext startAsync() { throw new UnsupportedOperationException(); }
        @Override public AsyncContext startAsync(ServletRequest req, ServletResponse res) { throw new UnsupportedOperationException(); }

        // ── HTTP upgrade / parts ──────────────────────────────────────────────────

        @Override public Collection<Part> getParts() { return List.of(); }
        @Override public Part getPart(String name) { return null; }
        @Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { throw new UnsupportedOperationException(); }
        @Override public String changeSessionId() { throw new UnsupportedOperationException(); }
        @Override public boolean authenticate(HttpServletResponse response) { throw new UnsupportedOperationException(); }
        @Override public void login(String username, String password) { throw new UnsupportedOperationException(); }
        @Override public void logout() { throw new UnsupportedOperationException(); }

        // ── Trailer fields ────────────────────────────────────────────────────────

        @Override public Map<String, String> getTrailerFields() { return Map.of(); }
        @Override public boolean isTrailerFieldsReady() { return true; }

        // ── Servlet connection / request ID (Servlet 6) ───────────────────────────

        @Override public String getRequestId() { return ""; }
        @Override public String getProtocolRequestId() { return ""; }
        @Override public ServletConnection getServletConnection() { return null; }

        // ── HTTP servlet mapping ──────────────────────────────────────────────────

        @Override
        public jakarta.servlet.http.HttpServletMapping getHttpServletMapping() {
            return new jakarta.servlet.http.HttpServletMapping() {
                @Override public String getMatchValue() { return ""; }
                @Override public String getPattern() { return ""; }
                @Override public String getServletName() { return ""; }
                @Override public MappingMatch getMappingMatch() { return null; }
            };
        }
    }
}
