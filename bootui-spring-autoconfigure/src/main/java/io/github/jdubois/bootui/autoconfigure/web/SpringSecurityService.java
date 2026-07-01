package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SpringSecurityAuthDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityEndpointDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityEndpointsReport;
import io.github.jdubois.bootui.core.dto.SpringSecurityExplainDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityFilterChainDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityReport;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.BufferedReader;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Exposes Spring Security filter chain configuration for the BootUI developer console.
 *
 * <p>Read-only. Never surfaces credentials, signing keys, or session identifiers.
 * Activated only when {@code spring-security-web} is on the classpath.</p>
 */
class SpringSecurityService {

    private static final Pattern AUTHORITIES_LIST = Pattern.compile("authorities=\\[([^\\]]*)\\]");
    private final ObjectProvider<FilterChainProxy> filterChainProxyProvider;
    private final ObjectProvider<AuthenticationProvider> authenticationProviderProvider;
    private final ObjectProvider<UserDetailsService> userDetailsServiceProvider;
    private final ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappingProvider;
    private final Environment environment;
    private final BootUiExposure exposure;
    private final BootUiSelfDataFilter selfDataFilter;

    SpringSecurityService(
            ObjectProvider<FilterChainProxy> filterChainProxyProvider,
            ObjectProvider<AuthenticationProvider> authenticationProviderProvider,
            ObjectProvider<UserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties) {
        this(
                filterChainProxyProvider,
                authenticationProviderProvider,
                userDetailsServiceProvider,
                handlerMappingProvider,
                environment,
                properties,
                BootUiSelfDataFilter.defaults());
    }

    SpringSecurityService(
            ObjectProvider<FilterChainProxy> filterChainProxyProvider,
            ObjectProvider<AuthenticationProvider> authenticationProviderProvider,
            ObjectProvider<UserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        this.filterChainProxyProvider = filterChainProxyProvider;
        this.authenticationProviderProvider = authenticationProviderProvider;
        this.userDetailsServiceProvider = userDetailsServiceProvider;
        this.handlerMappingProvider = handlerMappingProvider;
        this.environment = environment;
        this.exposure = new BootUiExposure(environment, properties);
        this.selfDataFilter = selfDataFilter;
    }

    public SpringSecurityReport security() {
        FilterChainProxy proxy = filterChainProxyProvider.getIfAvailable();
        if (proxy == null) {
            return new SpringSecurityReport(false, List.of(), null);
        }
        List<SecurityFilterChain> chains = proxy.getFilterChains();
        List<SpringSecurityFilterChainDto> chainDtos = new ArrayList<>(chains.size());
        for (int i = 0; i < chains.size(); i++) {
            SecurityFilterChain chain = chains.get(i);
            if (!selfDataFilter.shouldIncludeSecurityChain(matcherDescription(chain))) {
                continue;
            }
            chainDtos.add(toChainDto(i, chain));
        }
        return new SpringSecurityReport(true, chainDtos, buildAuth());
    }

    /**
     * Best-effort explain: given an HTTP method and path, returns the first matching chain
     * and its filter pipeline.
     *
     * <p>Matching is performed with a minimal request stub that covers method and path only.
     * Header- or session-based matchers may not match correctly; {@code bestEffort} is
     * {@code true} when the stub detected that such matchers were consulted.</p>
     */
    public SpringSecurityExplainDto explain(String method, String path) {
        FilterChainProxy proxy = filterChainProxyProvider.getIfAvailable();
        if (proxy == null) {
            return new SpringSecurityExplainDto(false, false, null, null, List.of());
        }
        if (!selfDataFilter.shouldIncludeSecurityEndpoint(List.of(path), null)) {
            return new SpringSecurityExplainDto(
                    false, false, null, "BootUI endpoints are hidden from this report", List.of());
        }
        ExplainRequest request = new ExplainRequest(method, path);
        List<SecurityFilterChain> chains = proxy.getFilterChains();
        for (int i = 0; i < chains.size(); i++) {
            SecurityFilterChain chain = chains.get(i);
            boolean matches;
            try {
                matches = chain.matches(request);
            } catch (Exception ex) {
                return new SpringSecurityExplainDto(
                        false,
                        true,
                        null,
                        "Chain " + i + " matcher threw " + ex.getClass().getSimpleName()
                                + " — requires more request context than available",
                        List.of());
            }
            if (matches) {
                return new SpringSecurityExplainDto(
                        true, request.isBestEffort(), i, matcherDescription(chain), filterNames(chain.getFilters()));
            }
        }
        return new SpringSecurityExplainDto(false, request.isBestEffort(), null, "No chain matched", List.of());
    }

    /**
     * Lists all HTTP endpoints together with the Spring Security authorization rule
     * applied to each one.
     *
     * <p>For every {@code RequestMappingInfo} known to the application's
     * {@link RequestMappingInfoHandlerMapping handler mappings}, the matching
     * {@link SecurityFilterChain} is located and its {@link AuthorizationFilter}
     * is inspected to derive the rule (permit/deny/authenticated/role/authority).
     * Resolution is best-effort: matchers depending on headers, cookies, or
     * session state may not be evaluated accurately, in which case
     * {@code bestEffort} is set on the endpoint entry.</p>
     */
    public SpringSecurityEndpointsReport endpoints() {
        FilterChainProxy proxy = filterChainProxyProvider.getIfAvailable();
        boolean springSecurityPresent = proxy != null;
        List<RequestMappingInfoHandlerMapping> handlerMappings =
                handlerMappingProvider.stream().toList();
        if (handlerMappings.isEmpty()) {
            return new SpringSecurityEndpointsReport(springSecurityPresent, false, 0, List.of());
        }

        List<SecurityFilterChain> chains = springSecurityPresent ? proxy.getFilterChains() : List.of();
        List<SpringSecurityEndpointDto> endpoints = new ArrayList<>();
        for (RequestMappingInfoHandlerMapping mapping : handlerMappings) {
            Map<RequestMappingInfo, HandlerMethod> methods;
            try {
                methods = mapping.getHandlerMethods();
            } catch (Exception ex) {
                continue;
            }
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : methods.entrySet()) {
                endpoints.addAll(describeEndpoint(entry.getKey(), entry.getValue(), chains));
            }
        }

        endpoints.sort(Comparator.comparing(SpringSecurityEndpointDto::pattern)
                .thenComparing(SpringSecurityEndpointDto::method));
        return new SpringSecurityEndpointsReport(springSecurityPresent, true, endpoints.size(), endpoints);
    }

    private List<SpringSecurityEndpointDto> describeEndpoint(
            RequestMappingInfo info, HandlerMethod handlerMethod, List<SecurityFilterChain> chains) {
        Set<String> patterns = extractPatterns(info);
        Set<String> methods = info.getMethodsCondition().getMethods().stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (methods.isEmpty()) {
            methods.add("ANY");
        }
        String handler = handlerMethod.getBeanType().getSimpleName() + "#"
                + handlerMethod.getMethod().getName();

        List<SpringSecurityEndpointDto> result = new ArrayList<>();
        for (String pattern : patterns) {
            for (String method : methods) {
                if (!selfDataFilter.shouldIncludeSecurityEndpoint(List.of(pattern), handler)) {
                    continue;
                }
                result.add(resolveEndpoint(method, pattern, handler, chains));
            }
        }
        return result;
    }

    private Set<String> extractPatterns(RequestMappingInfo info) {
        Set<String> patterns = new LinkedHashSet<>();
        if (info.getPathPatternsCondition() != null) {
            info.getPathPatternsCondition().getPatterns().forEach(p -> patterns.add(p.getPatternString()));
        }
        if (patterns.isEmpty()) {
            patterns.add("/**");
        }
        return patterns;
    }

    private SpringSecurityEndpointDto resolveEndpoint(
            String method, String pattern, String handler, List<SecurityFilterChain> chains) {
        if (chains.isEmpty()) {
            return new SpringSecurityEndpointDto(
                    method,
                    pattern,
                    handler,
                    false,
                    "unsecured",
                    List.of(),
                    null,
                    null,
                    "No Spring Security filter chains configured",
                    false);
        }
        ExplainRequest request = new ExplainRequest(method, pattern);
        for (int i = 0; i < chains.size(); i++) {
            SecurityFilterChain chain = chains.get(i);
            boolean matches;
            try {
                matches = chain.matches(request);
            } catch (Exception ex) {
                return new SpringSecurityEndpointDto(
                        method,
                        pattern,
                        handler,
                        true,
                        "unknown",
                        List.of(),
                        i,
                        matcherDescription(chain),
                        "Chain matcher threw " + ex.getClass().getSimpleName(),
                        true);
            }
            if (matches) {
                AuthorizationFilter authFilter = findAuthorizationFilter(chain);
                if (authFilter == null) {
                    return new SpringSecurityEndpointDto(
                            method,
                            pattern,
                            handler,
                            true,
                            "unknown",
                            List.of(),
                            i,
                            matcherDescription(chain),
                            "Chain has no AuthorizationFilter",
                            request.isBestEffort());
                }
                return classifyRule(method, pattern, handler, i, chain, authFilter, request);
            }
        }
        return new SpringSecurityEndpointDto(
                method,
                pattern,
                handler,
                false,
                "unsecured",
                List.of(),
                null,
                null,
                "No Spring Security filter chain matched",
                request.isBestEffort());
    }

    private AuthorizationFilter findAuthorizationFilter(SecurityFilterChain chain) {
        for (jakarta.servlet.Filter filter : chain.getFilters()) {
            if (filter instanceof AuthorizationFilter af) {
                return af;
            }
        }
        return null;
    }

    /**
     * Simulates an authorization decision against {@code authFilter}'s
     * {@link AuthorizationManager} using synthetic authentications to classify the
     * rule that applies to this endpoint. The manager itself is also inspected via
     * its {@link Object#toString() toString()} when it is a well-known type, so role
     * and authority names can be reported.
     */
    private SpringSecurityEndpointDto classifyRule(
            String method,
            String pattern,
            String handler,
            int chainIndex,
            SecurityFilterChain chain,
            AuthorizationFilter authFilter,
            ExplainRequest request) {
        AuthorizationManager<HttpServletRequest> manager = authFilter.getAuthorizationManager();

        boolean anonymousGranted = simulate(manager, anonymousAuth(), request);
        if (anonymousGranted) {
            return new SpringSecurityEndpointDto(
                    method,
                    pattern,
                    handler,
                    true,
                    "permitAll",
                    List.of(),
                    chainIndex,
                    matcherDescription(chain),
                    null,
                    request.isBestEffort());
        }

        boolean authenticatedGranted = simulate(manager, authenticatedAuth(List.of()), request);
        if (authenticatedGranted) {
            return new SpringSecurityEndpointDto(
                    method,
                    pattern,
                    handler,
                    true,
                    "authenticated",
                    List.of(),
                    chainIndex,
                    matcherDescription(chain),
                    null,
                    request.isBestEffort());
        }

        // Try to extract role/authority names from the AuthorizationManager's toString().
        // Both AuthorityAuthorizationManager and AuthoritiesAuthorizationManager expose
        // their required authorities through toString() in Spring Security 7.x.
        AuthoritySpec spec = extractAuthorities(manager);
        if (spec != null && !spec.authorities.isEmpty()) {
            boolean roleGranted = simulate(manager, authenticatedAuth(spec.authorities), request);
            if (roleGranted) {
                List<String> exposed = new ArrayList<>(spec.authorities.size());
                String rule = spec.allRolePrefixed ? "hasRole" : "hasAuthority";
                for (String authority : spec.authorities) {
                    exposed.add(
                            spec.allRolePrefixed && authority.startsWith("ROLE_")
                                    ? authority.substring("ROLE_".length())
                                    : authority);
                }
                return new SpringSecurityEndpointDto(
                        method,
                        pattern,
                        handler,
                        true,
                        rule,
                        exposed,
                        chainIndex,
                        matcherDescription(chain),
                        null,
                        request.isBestEffort());
            }
        }

        // No synthetic principal could obtain access — most likely denyAll, or a custom manager.
        boolean superGranted =
                simulate(manager, authenticatedAuth(List.of("ROLE_ADMIN", "ROLE_USER", "SCOPE_ADMIN")), request);
        String rule = superGranted ? "custom" : "denyAll";
        return new SpringSecurityEndpointDto(
                method,
                pattern,
                handler,
                true,
                rule,
                List.of(),
                chainIndex,
                matcherDescription(chain),
                "Managed by " + manager.getClass().getSimpleName(),
                request.isBestEffort());
    }

    private boolean simulate(
            AuthorizationManager<HttpServletRequest> manager,
            Authentication authentication,
            HttpServletRequest request) {
        try {
            AuthorizationResult result = manager.authorize(() -> authentication, request);
            return result != null && result.isGranted();
        } catch (Exception ex) {
            return false;
        }
    }

    private Authentication anonymousAuth() {
        return new AnonymousAuthenticationToken(
                "bootui-explain", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    private Authentication authenticatedAuth(List<String> authorities) {
        List<SimpleGrantedAuthority> granted =
                authorities.stream().map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated("bootui-explain", "n/a", granted);
    }

    private AuthoritySpec extractAuthorities(AuthorizationManager<?> manager) {
        // Try a public getter first, in case future versions expose one.
        for (String getterName : new String[] {"getAuthorities"}) {
            try {
                Method m = manager.getClass().getMethod(getterName);
                Object value = m.invoke(manager);
                List<String> names = readAuthorityNames(value);
                if (names != null && !names.isEmpty()) {
                    return new AuthoritySpec(names, names.stream().allMatch(n -> n.startsWith("ROLE_")));
                }
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        // Parse toString() for AuthorityAuthorizationManager/AuthoritiesAuthorizationManager.
        String descriptor = String.valueOf(manager);
        Matcher m = AUTHORITIES_LIST.matcher(descriptor);
        if (m.find()) {
            String[] tokens = m.group(1).split(",");
            List<String> names = new ArrayList<>();
            for (String token : tokens) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
            if (!names.isEmpty()) {
                return new AuthoritySpec(names, names.stream().allMatch(n -> n.startsWith("ROLE_")));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> readAuthorityNames(Object value) {
        if (!(value instanceof Collection<?> coll)) {
            return null;
        }
        List<String> names = new ArrayList<>(coll.size());
        for (Object element : coll) {
            if (element == null) {
                continue;
            }
            // Could be GrantedAuthority or String.
            try {
                Method m = element.getClass().getMethod("getAuthority");
                Object authority = m.invoke(element);
                if (authority != null) {
                    names.add(authority.toString());
                    continue;
                }
            } catch (ReflectiveOperationException ignored) {
                // not a GrantedAuthority; treat as plain string
            }
            names.add(element.toString());
        }
        return names;
    }

    private SpringSecurityFilterChainDto toChainDto(int order, SecurityFilterChain chain) {
        return new SpringSecurityFilterChainDto(
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
        return filters.stream().map(f -> f.getClass().getSimpleName()).toList();
    }

    private boolean hasFilter(SecurityFilterChain chain, String simpleClassName) {
        return chain.getFilters().stream()
                .anyMatch(f -> f.getClass().getSimpleName().equals(simpleClassName));
    }

    private SpringSecurityAuthDto buildAuth() {
        List<String> providerTypes = authenticationProviderProvider.stream()
                .map(p -> p.getClass().getName())
                .sorted()
                .toList();
        List<String> udsTypes = userDetailsServiceProvider.stream()
                .map(u -> u.getClass().getName())
                .sorted()
                .toList();
        // spring.security.user.name is a username, not a secret; expose it to help
        // developers identify the auto-generated user when no custom UserDetailsService
        // is configured. Never read spring.security.user.password.
        String configuredUsername = null;
        if (exposure.valueExposure() != ValueExposure.METADATA_ONLY) {
            configuredUsername = environment.getProperty("spring.security.user.name");
        }
        return new SpringSecurityAuthDto(providerTypes, udsTypes, configuredUsername);
    }

    private record AuthoritySpec(List<String> authorities, boolean allRolePrefixed) {}

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

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public String getServletPath() {
            return path;
        }

        @Override
        public String getRequestURI() {
            return path;
        }

        @Override
        public StringBuffer getRequestURL() {
            return new StringBuffer("http://localhost" + path);
        }

        @Override
        public String getContextPath() {
            return "";
        }

        @Override
        public String getPathInfo() {
            return null;
        }

        @Override
        public String getPathTranslated() {
            return null;
        }

        @Override
        public String getQueryString() {
            return null;
        }

        // ── Remote / connection ───────────────────────────────────────────────────

        @Override
        public String getRemoteAddr() {
            return "127.0.0.1";
        }

        @Override
        public String getRemoteHost() {
            return "localhost";
        }

        @Override
        public int getRemotePort() {
            return 0;
        }

        @Override
        public String getLocalAddr() {
            return "127.0.0.1";
        }

        @Override
        public String getLocalName() {
            return "localhost";
        }

        @Override
        public int getLocalPort() {
            return 80;
        }

        @Override
        public String getScheme() {
            return "http";
        }

        @Override
        public String getServerName() {
            return "localhost";
        }

        @Override
        public int getServerPort() {
            return 80;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public boolean isSecure() {
            return false;
        }

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

        @Override
        public long getDateHeader(String name) {
            bestEffort = true;
            return -1L;
        }

        @Override
        public int getIntHeader(String name) {
            bestEffort = true;
            return -1;
        }

        // ── Session / auth — mark best-effort ─────────────────────────────────────

        @Override
        public HttpSession getSession(boolean create) {
            bestEffort = true;
            return null;
        }

        @Override
        public HttpSession getSession() {
            bestEffort = true;
            return null;
        }

        @Override
        public String getRequestedSessionId() {
            return null;
        }

        @Override
        public boolean isRequestedSessionIdValid() {
            return false;
        }

        @Override
        public boolean isRequestedSessionIdFromCookie() {
            return false;
        }

        @Override
        public boolean isRequestedSessionIdFromURL() {
            return false;
        }

        @Override
        public Principal getUserPrincipal() {
            return null;
        }

        @Override
        public String getRemoteUser() {
            return null;
        }

        @Override
        public boolean isUserInRole(String role) {
            return false;
        }

        @Override
        public String getAuthType() {
            return null;
        }

        @Override
        public Cookie[] getCookies() {
            return null;
        }

        // ── Attributes ────────────────────────────────────────────────────────────

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public void setAttribute(String name, Object o) {
            /* no-op */
        }

        @Override
        public void removeAttribute(String name) {
            /* no-op */
        }

        // ── Parameters ────────────────────────────────────────────────────────────

        @Override
        public String getParameter(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public String[] getParameterValues(String name) {
            return null;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Map.of();
        }

        // ── Content / encoding ────────────────────────────────────────────────────

        @Override
        public String getCharacterEncoding() {
            return "UTF-8";
        }

        @Override
        public void setCharacterEncoding(String env) {
            /* no-op */
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1L;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public ServletInputStream getInputStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public BufferedReader getReader() {
            throw new UnsupportedOperationException();
        }

        // ── Locale ────────────────────────────────────────────────────────────────

        @Override
        public Locale getLocale() {
            return Locale.getDefault();
        }

        @Override
        public Enumeration<Locale> getLocales() {
            return Collections.enumeration(List.of(Locale.getDefault()));
        }

        // ── Dispatch ──────────────────────────────────────────────────────────────

        @Override
        public DispatcherType getDispatcherType() {
            return DispatcherType.REQUEST;
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String path) {
            return null;
        }

        @Override
        public ServletContext getServletContext() {
            return null;
        }

        @Override
        public boolean isAsyncSupported() {
            return false;
        }

        @Override
        public boolean isAsyncStarted() {
            return false;
        }

        @Override
        public AsyncContext getAsyncContext() {
            return null;
        }

        @Override
        public AsyncContext startAsync() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AsyncContext startAsync(ServletRequest req, ServletResponse res) {
            throw new UnsupportedOperationException();
        }

        // ── HTTP upgrade / parts ──────────────────────────────────────────────────

        @Override
        public Collection<Part> getParts() {
            return List.of();
        }

        @Override
        public Part getPart(String name) {
            return null;
        }

        @Override
        public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String changeSessionId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean authenticate(HttpServletResponse response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void login(String username, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void logout() {
            throw new UnsupportedOperationException();
        }

        // ── Trailer fields ────────────────────────────────────────────────────────

        @Override
        public Map<String, String> getTrailerFields() {
            return Map.of();
        }

        @Override
        public boolean isTrailerFieldsReady() {
            return true;
        }

        // ── Servlet connection / request ID (Servlet 6) ───────────────────────────

        @Override
        public String getRequestId() {
            return "";
        }

        @Override
        public String getProtocolRequestId() {
            return "";
        }

        @Override
        public ServletConnection getServletConnection() {
            return null;
        }

        // ── HTTP servlet mapping ──────────────────────────────────────────────────

        @Override
        public jakarta.servlet.http.HttpServletMapping getHttpServletMapping() {
            return new jakarta.servlet.http.HttpServletMapping() {
                @Override
                public String getMatchValue() {
                    return "";
                }

                @Override
                public String getPattern() {
                    return "";
                }

                @Override
                public String getServletName() {
                    return "";
                }

                @Override
                public MappingMatch getMappingMatch() {
                    return null;
                }
            };
        }
    }
}
