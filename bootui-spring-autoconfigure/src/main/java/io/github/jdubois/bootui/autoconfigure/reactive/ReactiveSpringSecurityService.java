package io.github.jdubois.bootui.autoconfigure.reactive;

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
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.authorization.AuthorizationWebFilter;
import org.springframework.security.web.server.util.matcher.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code SpringSecurityService}: maps {@link SecurityWebFilterChain}
 * / {@link WebFilterChainProxy} information into the existing Spring Security panel DTO contract.
 *
 * <p>Reduced-fidelity notes:</p>
 * <ul>
 *   <li>Reactive filters are {@link WebFilter} beans, not {@code javax.servlet.Filter}; simple class
 *       names are reported faithfully in the filter list.</li>
 *   <li>The {@code explain} endpoint uses a best-effort stub {@link ServerWebExchange} that covers
 *       path- and method-based matchers ({@code PathPatternParserServerWebExchangeMatcher}). Matchers
 *       that inspect headers, cookies, or session state may produce inaccurate results;
 *       {@code bestEffort} is set whenever such access is detected.</li>
 *   <li>The {@code endpoints} listing uses the reactive {@link RequestMappingHandlerMapping} where
 *       available; authorization simulation targets the reactive {@link ReactiveAuthorizationManager}
 *       rather than the servlet {@code AuthorizationManager}.</li>
 *   <li>{@code sessionManagementPresent} detects reactive session-context filters
 *       ({@code WebSessionServerSecurityContextRepository} is embedded in the security context save
 *       filter, so it does not appear as a standalone filter name; instead the
 *       {@code SecurityContextServerWebExchangeWebFilter} is detected, which is always present on a
 *       secured reactive chain).</li>
 * </ul>
 *
 * <p>Read-only. Never surfaces credentials, signing keys, or session identifiers.</p>
 */
class ReactiveSpringSecurityService {

    private static final Pattern AUTHORITIES_LIST = Pattern.compile("authorities=\\[([^\\]]*)\\]");

    private final ObjectProvider<WebFilterChainProxy> filterChainProxyProvider;
    private final ObjectProvider<ReactiveAuthenticationManager> authManagerProvider;
    private final ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
    private final Environment environment;
    private final BootUiExposure exposure;
    private final BootUiSelfDataFilter selfDataFilter;

    ReactiveSpringSecurityService(
            ObjectProvider<WebFilterChainProxy> filterChainProxyProvider,
            ObjectProvider<ReactiveAuthenticationManager> authManagerProvider,
            ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties) {
        this(
                filterChainProxyProvider,
                authManagerProvider,
                userDetailsServiceProvider,
                handlerMappingProvider,
                environment,
                properties,
                BootUiSelfDataFilter.defaults());
    }

    ReactiveSpringSecurityService(
            ObjectProvider<WebFilterChainProxy> filterChainProxyProvider,
            ObjectProvider<ReactiveAuthenticationManager> authManagerProvider,
            ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        this.filterChainProxyProvider = filterChainProxyProvider;
        this.authManagerProvider = authManagerProvider;
        this.userDetailsServiceProvider = userDetailsServiceProvider;
        this.handlerMappingProvider = handlerMappingProvider;
        this.environment = environment;
        this.exposure = new BootUiExposure(environment, properties);
        this.selfDataFilter = selfDataFilter;
    }

    public SpringSecurityReport security() {
        WebFilterChainProxy proxy = filterChainProxyProvider.getIfAvailable();
        if (proxy == null) {
            return new SpringSecurityReport(false, List.of(), null);
        }
        List<SecurityWebFilterChain> chains = proxy.getWebFilterChains();
        List<SpringSecurityFilterChainDto> chainDtos = new ArrayList<>(chains.size());
        for (int i = 0; i < chains.size(); i++) {
            SecurityWebFilterChain chain = chains.get(i);
            if (!selfDataFilter.shouldIncludeSecurityChain(matcherDescription(chain))) {
                continue;
            }
            chainDtos.add(toChainDto(i, chain));
        }
        return new SpringSecurityReport(true, chainDtos, buildAuth());
    }

    /**
     * Best-effort explain: given an HTTP method and path, returns the first matching chain and its
     * filter pipeline.
     *
     * <p>Uses a minimal stub {@link ServerWebExchange} covering path and method only. Matchers that
     * read headers, cookies, or session attributes may not match correctly; {@code bestEffort} is set
     * when such access is detected.</p>
     */
    public SpringSecurityExplainDto explain(String method, String path) {
        WebFilterChainProxy proxy = filterChainProxyProvider.getIfAvailable();
        if (proxy == null) {
            return new SpringSecurityExplainDto(false, false, null, null, List.of());
        }
        if (!selfDataFilter.shouldIncludeSecurityEndpoint(List.of(path), null)) {
            return new SpringSecurityExplainDto(
                    false, false, null, "BootUI endpoints are hidden from this report", List.of());
        }
        ExplainExchange exchange = new ExplainExchange(method, path);
        List<SecurityWebFilterChain> chains = proxy.getWebFilterChains();
        for (int i = 0; i < chains.size(); i++) {
            SecurityWebFilterChain chain = chains.get(i);
            ServerWebExchangeMatcher matcher = exchangeMatcher(chain);
            if (matcher == null) {
                // Unknown chain type — report as catch-all best-effort match.
                List<WebFilter> filters = filtersOf(chain);
                return new SpringSecurityExplainDto(
                        true, true, i, matcherDescription(chain), filterNames(filters));
            }
            ServerWebExchangeMatcher.MatchResult result;
            try {
                result = matcher.matches(exchange).block();
            } catch (Exception ex) {
                return new SpringSecurityExplainDto(
                        false,
                        true,
                        null,
                        "Chain " + i + " matcher threw " + ex.getClass().getSimpleName()
                                + " — requires more request context than available",
                        List.of());
            }
            if (result != null && result.isMatch()) {
                List<WebFilter> filters = filtersOf(chain);
                return new SpringSecurityExplainDto(
                        true,
                        exchange.isBestEffort(),
                        i,
                        matcherDescription(chain),
                        filterNames(filters));
            }
        }
        return new SpringSecurityExplainDto(
                false, exchange.isBestEffort(), null, "No chain matched", List.of());
    }

    /**
     * Lists all HTTP endpoints discovered via reactive {@link RequestMappingHandlerMapping} together
     * with the Spring Security authorization rule applied to each one.
     */
    public SpringSecurityEndpointsReport endpoints() {
        WebFilterChainProxy proxy = filterChainProxyProvider.getIfAvailable();
        boolean springSecurityPresent = proxy != null;
        List<RequestMappingHandlerMapping> handlerMappings =
                handlerMappingProvider.stream().toList();
        if (handlerMappings.isEmpty()) {
            return new SpringSecurityEndpointsReport(springSecurityPresent, false, 0, List.of());
        }

        List<SecurityWebFilterChain> chains = springSecurityPresent ? proxy.getWebFilterChains() : List.of();
        List<SpringSecurityEndpointDto> endpoints = new ArrayList<>();
        for (RequestMappingHandlerMapping mapping : handlerMappings) {
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
            RequestMappingInfo info, HandlerMethod handlerMethod, List<SecurityWebFilterChain> chains) {
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
        // Spring WebFlux 6.x+ uses PathPatternsRequestCondition as the primary condition.
        // Fall back to getPatternsCondition() if it is non-null for older configurations.
        if (info.getPathPatternsCondition() != null) {
            info.getPathPatternsCondition().getPatterns().forEach(p -> patterns.add(p.getPatternString()));
        } else if (info.getPatternsCondition() != null) {
            info.getPatternsCondition().getPatterns().forEach(p -> patterns.add(p.getPatternString()));
        }
        if (patterns.isEmpty()) {
            patterns.add("/**");
        }
        return patterns;
    }

    private SpringSecurityEndpointDto resolveEndpoint(
            String method, String pattern, String handler, List<SecurityWebFilterChain> chains) {
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
        ExplainExchange exchange = new ExplainExchange(method, pattern);
        for (int i = 0; i < chains.size(); i++) {
            SecurityWebFilterChain chain = chains.get(i);
            ServerWebExchangeMatcher matcher = exchangeMatcher(chain);
            boolean matches;
            if (matcher == null) {
                matches = true; // unknown chain type; treat as catch-all
            } else {
                try {
                    ServerWebExchangeMatcher.MatchResult result = matcher.matches(exchange).block();
                    matches = result != null && result.isMatch();
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
            }
            if (matches) {
                AuthorizationWebFilter authFilter = findAuthorizationFilter(chain);
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
                            "Chain has no AuthorizationWebFilter",
                            exchange.isBestEffort());
                }
                return classifyRule(method, pattern, handler, i, chain, authFilter, exchange);
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
                exchange.isBestEffort());
    }

    private AuthorizationWebFilter findAuthorizationFilter(SecurityWebFilterChain chain) {
        for (WebFilter filter : filtersOf(chain)) {
            if (filter instanceof AuthorizationWebFilter af) {
                return af;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private SpringSecurityEndpointDto classifyRule(
            String method,
            String pattern,
            String handler,
            int chainIndex,
            SecurityWebFilterChain chain,
            AuthorizationWebFilter authFilter,
            ExplainExchange exchange) {
        ReactiveAuthorizationManager<AuthorizationContext> manager;
        try {
            manager = (ReactiveAuthorizationManager<AuthorizationContext>) authFilter.getAuthorizationManager();
        } catch (Exception ex) {
            return new SpringSecurityEndpointDto(
                    method,
                    pattern,
                    handler,
                    true,
                    "unknown",
                    List.of(),
                    chainIndex,
                    matcherDescription(chain),
                    "Could not access authorization manager: " + ex.getMessage(),
                    exchange.isBestEffort());
        }

        boolean anonymousGranted = simulateReactive(manager, anonymousAuth(), exchange);
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
                    exchange.isBestEffort());
        }

        boolean authenticatedGranted = simulateReactive(manager, authenticatedAuth(List.of()), exchange);
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
                    exchange.isBestEffort());
        }

        // Try to determine required roles from the manager's toString().
        AuthoritySpec spec = extractAuthorities(manager);
        if (spec != null && !spec.authorities().isEmpty()) {
            boolean roleGranted = simulateReactive(manager, authenticatedAuth(spec.authorities()), exchange);
            if (roleGranted) {
                List<String> exposed = new ArrayList<>(spec.authorities().size());
                String rule = spec.allRolePrefixed() ? "hasRole" : "hasAuthority";
                for (String authority : spec.authorities()) {
                    exposed.add(
                            spec.allRolePrefixed() && authority.startsWith("ROLE_")
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
                        exchange.isBestEffort());
            }
        }

        // No synthetic principal could obtain access — likely denyAll or custom manager.
        boolean superGranted = simulateReactive(
                manager, authenticatedAuth(List.of("ROLE_ADMIN", "ROLE_USER", "SCOPE_ADMIN")), exchange);
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
                exchange.isBestEffort());
    }

    private boolean simulateReactive(
            ReactiveAuthorizationManager<AuthorizationContext> manager,
            Authentication authentication,
            ServerWebExchange exchange) {
        try {
            AuthorizationContext context = new AuthorizationContext(exchange);
            var result = manager.check(Mono.just(authentication), context).block();
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

    private AuthoritySpec extractAuthorities(Object manager) {
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

    private SpringSecurityFilterChainDto toChainDto(int order, SecurityWebFilterChain chain) {
        List<WebFilter> filters = filtersOf(chain);
        return new SpringSecurityFilterChainDto(
                order,
                matcherDescription(chain),
                matcherTypeName(chain),
                filterNames(filters),
                hasFilter(filters, "CsrfWebFilter"),
                hasFilter(filters, "CorsWebFilter"),
                // Reactive session management is handled by SecurityContextServerWebExchangeWebFilter
                // (which persists the security context to the WebSession via
                // WebSessionServerSecurityContextRepository). There is no separate
                // SessionManagementWebFilter in the reactive chain, so we detect the presence of the
                // security context save filter as the session-management signal.
                hasFilter(filters, "SecurityContextServerWebExchangeWebFilter"));
    }

    private String matcherDescription(SecurityWebFilterChain chain) {
        if (chain instanceof MatcherSecurityWebFilterChain mswfc) {
            return mswfc.getExchangeMatcher().toString();
        }
        return "(custom chain: " + chain.getClass().getSimpleName() + ")";
    }

    private String matcherTypeName(SecurityWebFilterChain chain) {
        if (chain instanceof MatcherSecurityWebFilterChain mswfc) {
            return mswfc.getExchangeMatcher().getClass().getSimpleName();
        }
        return chain.getClass().getSimpleName();
    }

    private ServerWebExchangeMatcher exchangeMatcher(SecurityWebFilterChain chain) {
        if (chain instanceof MatcherSecurityWebFilterChain mswfc) {
            return mswfc.getExchangeMatcher();
        }
        return null;
    }

    private List<WebFilter> filtersOf(SecurityWebFilterChain chain) {
        try {
            List<WebFilter> filters = chain.getWebFilters().collectList().block();
            return filters != null ? filters : List.of();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> filterNames(List<WebFilter> filters) {
        return filters.stream().map(f -> f.getClass().getSimpleName()).toList();
    }

    private boolean hasFilter(List<WebFilter> filters, String simpleClassName) {
        return filters.stream().anyMatch(f -> f.getClass().getSimpleName().equals(simpleClassName));
    }

    private SpringSecurityAuthDto buildAuth() {
        List<String> providerTypes = authManagerProvider.stream()
                .map(m -> m.getClass().getName())
                .sorted()
                .toList();
        List<String> udsTypes = userDetailsServiceProvider.stream()
                .map(u -> u.getClass().getName())
                .sorted()
                .toList();
        // spring.security.user.name is a username, not a secret; expose it to help developers
        // identify the auto-configured user when no custom ReactiveUserDetailsService is wired.
        String configuredUsername = null;
        if (exposure.valueExposure() != ValueExposure.METADATA_ONLY) {
            configuredUsername = environment.getProperty("spring.security.user.name");
        }
        return new SpringSecurityAuthDto(providerTypes, udsTypes, configuredUsername);
    }

    private record AuthoritySpec(List<String> authorities, boolean allRolePrefixed) {}

    // ── Best-effort stub ServerWebExchange for explain / endpoint matching ────────

    /**
     * Minimal {@link ServerWebExchange} stub used for best-effort chain matching in the explain and
     * endpoints methods.
     *
     * <p>Provides method and path to satisfy {@code PathPatternParserServerWebExchangeMatcher} (the
     * most common reactive matcher). Any access to headers, cookies, session, or response marks
     * {@code bestEffort=true} so the caller can communicate reduced confidence to the client.</p>
     */
    private static final class ExplainExchange implements ServerWebExchange {

        private final ExplainHttpRequest request;
        private boolean bestEffort;

        ExplainExchange(String method, String path) {
            this.request = new ExplainHttpRequest(method, path, this);
        }

        boolean isBestEffort() {
            return bestEffort || request.bestEffort;
        }

        void markBestEffort() {
            this.bestEffort = true;
        }

        @Override
        public ServerHttpRequest getRequest() {
            return request;
        }

        @Override
        public ServerHttpResponse getResponse() {
            bestEffort = true;
            throw new UnsupportedOperationException("explain stub — no response");
        }

        @Override
        public Map<String, Object> getAttributes() {
            return new java.util.concurrent.ConcurrentHashMap<>();
        }

        @Override
        public Mono<WebSession> getSession() {
            bestEffort = true;
            return Mono.empty();
        }

        @Override
        public <T extends java.security.Principal> Mono<T> getPrincipal() {
            return Mono.empty();
        }

        @Override
        public Flux<org.springframework.http.codec.multipart.Part> getMultipartData() {
            bestEffort = true;
            return Flux.empty();
        }

        @Override
        public Mono<MultiValueMap<String, String>> getFormData() {
            bestEffort = true;
            return Mono.just(new LinkedMultiValueMap<>());
        }

        @Override
        public org.springframework.context.i18n.LocaleContext getLocaleContext() {
            return () -> Locale.getDefault();
        }

        @Override
        public org.springframework.web.server.i18n.LocaleContextResolver getLocaleContextResolver() {
            bestEffort = true;
            return null;
        }

        @Override
        public boolean isNotModified() {
            return false;
        }

        @Override
        public boolean checkNotModified(HttpHeaders headers) {
            return false;
        }

        @Override
        public boolean checkNotModified(String etag) {
            return false;
        }

        @Override
        public boolean checkNotModified(java.time.Instant lastModified) {
            return false;
        }

        @Override
        public boolean checkNotModified(String etag, java.time.Instant lastModified) {
            return false;
        }

        @Override
        public String transformUrl(String url) {
            return url;
        }

        @Override
        public void addUrlTransformer(java.util.function.Function<String, String> transformer) {
            // no-op
        }

        @Override
        public String getLogPrefix() {
            return "";
        }
    }

    /**
     * Minimal {@link ServerHttpRequest} stub covering path and method for best-effort explain.
     */
    private static final class ExplainHttpRequest implements ServerHttpRequest {

        private final HttpMethod method;
        private final URI uri;
        private final RequestPath path;
        private final ExplainExchange parent;
        boolean bestEffort;

        ExplainHttpRequest(String methodStr, String pathStr, ExplainExchange parent) {
            this.method = methodStr != null
                    ? HttpMethod.valueOf(methodStr.toUpperCase(Locale.ROOT))
                    : HttpMethod.GET;
            String normalizedPath = pathStr != null ? (pathStr.startsWith("/") ? pathStr : "/" + pathStr) : "/";
            this.uri = URI.create("http://localhost" + normalizedPath);
            this.path = RequestPath.parse(this.uri, "");
            this.parent = parent;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public RequestPath getPath() {
            return path;
        }

        @Override
        public String getId() {
            return "bootui-explain";
        }

        @Override
        public HttpHeaders getHeaders() {
            bestEffort = true;
            return HttpHeaders.EMPTY;
        }

        @Override
        public MultiValueMap<String, String> getQueryParams() {
            return new LinkedMultiValueMap<>();
        }

        @Override
        public MultiValueMap<String, ResponseCookie> getCookies() {
            bestEffort = true;
            return new LinkedMultiValueMap<>();
        }

        @Override
        public java.net.InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public java.net.InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        @org.springframework.lang.Nullable
        public org.springframework.http.server.reactive.SslInfo getSslInfo() {
            return null;
        }

        @Override
        public Flux<org.springframework.core.io.buffer.DataBuffer> getBody() {
            parent.markBestEffort();
            return Flux.empty();
        }

        @Override
        public ServerHttpRequest.Builder mutate() {
            throw new UnsupportedOperationException("explain stub — mutation not supported");
        }

        @Override
        public MediaType getContentType() {
            return null;
        }
    }
}
