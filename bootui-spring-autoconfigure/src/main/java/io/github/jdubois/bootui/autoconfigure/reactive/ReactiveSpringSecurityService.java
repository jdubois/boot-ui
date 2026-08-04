package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SpringSecurityAuthDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityEndpointDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityEndpointsReport;
import io.github.jdubois.bootui.core.dto.SpringSecurityExplainDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityFilterChainDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityReport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationWebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Non-blocking WebFlux sibling of {@code SpringSecurityService}. It maps ordered
 * {@link SecurityWebFilterChain} beans into the existing raw Spring Security panel contract without
 * reflecting into {@code WebFilterChainProxy} or blocking a Reactor Netty event-loop thread.
 *
 * <p>The reactive API does not expose a chain's matcher or an {@link AuthorizationWebFilter}'s
 * manager directly. This service uses bounded reflection for those two read-only metadata seams,
 * but executes matching and authorization through the public reactive APIs. Unknown custom matcher
 * descriptions are reduced to their type name so their {@code toString()} cannot disclose a
 * configured header, token, or other sensitive value.</p>
 */
class ReactiveSpringSecurityService {

    private static final Logger log = LoggerFactory.getLogger(ReactiveSpringSecurityService.class);
    private static final Pattern AUTHORITIES_LIST = Pattern.compile("authorities=\\[([^\\]]*)\\]");
    private static final Pattern HTTP_METHOD_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Z-]+");
    private static final String SPRING_MATCHER_PACKAGE = "org.springframework.security.web.server.util.matcher.";
    private static final String CONFIGURED_USERNAME_PROPERTY = "spring.security.user.name";

    private final ObjectProvider<SecurityWebFilterChain> filterChainProvider;
    private final ObjectProvider<ReactiveAuthenticationManager> authManagerProvider;
    private final ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
    private final Environment environment;
    private final BootUiExposure exposure;
    private final BootUiSelfDataFilter selfDataFilter;
    private final SecretMasker masker = new SecretMasker();

    ReactiveSpringSecurityService(
            ObjectProvider<SecurityWebFilterChain> filterChainProvider,
            ObjectProvider<ReactiveAuthenticationManager> authManagerProvider,
            ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties) {
        this(
                filterChainProvider,
                authManagerProvider,
                userDetailsServiceProvider,
                handlerMappingProvider,
                environment,
                properties,
                BootUiSelfDataFilter.defaults());
    }

    ReactiveSpringSecurityService(
            ObjectProvider<SecurityWebFilterChain> filterChainProvider,
            ObjectProvider<ReactiveAuthenticationManager> authManagerProvider,
            ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        this.filterChainProvider = filterChainProvider;
        this.authManagerProvider = authManagerProvider;
        this.userDetailsServiceProvider = userDetailsServiceProvider;
        this.handlerMappingProvider = handlerMappingProvider;
        this.environment = environment;
        this.exposure = new BootUiExposure(environment, properties);
        this.selfDataFilter = selfDataFilter;
    }

    Mono<SpringSecurityReport> security() {
        return observeApplicationChains().map(chains -> {
            if (chains.isEmpty()) {
                return new SpringSecurityReport(false, List.of(), null);
            }
            List<SpringSecurityFilterChainDto> chainDtos =
                    chains.stream().map(this::toChainDto).toList();
            return new SpringSecurityReport(true, chainDtos, buildAuth());
        });
    }

    Mono<SpringSecurityExplainDto> explain(String method, String path, ServerWebExchange templateExchange) {
        return Mono.defer(() -> {
            ExplainExchange exchange = new ExplainExchange(templateExchange, method, path, true);
            if (!selfDataFilter.shouldIncludeSecurityEndpoint(List.of(exchange.path()), null)) {
                return Mono.just(new SpringSecurityExplainDto(
                        false, false, null, "BootUI endpoints are hidden from this report", List.of()));
            }
            return observeApplicationChains().flatMap(chains -> explain(chains, exchange));
        });
    }

    Mono<SpringSecurityEndpointsReport> endpoints(ServerWebExchange templateExchange) {
        return observeApplicationChains().flatMap(chains -> {
            boolean springSecurityPresent = !chains.isEmpty();
            List<RequestMappingHandlerMapping> handlerMappings =
                    handlerMappingProvider.stream().toList();
            if (handlerMappings.isEmpty()) {
                return Mono.just(new SpringSecurityEndpointsReport(springSecurityPresent, false, 0, List.of()));
            }

            List<EndpointCandidate> candidates = new ArrayList<>();
            for (RequestMappingHandlerMapping mapping : handlerMappings) {
                for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                        mapping.getHandlerMethods().entrySet()) {
                    candidates.addAll(describeEndpoints(entry.getKey(), entry.getValue()));
                }
            }

            return Flux.fromIterable(candidates)
                    .concatMap(candidate -> resolveEndpoint(candidate, chains, templateExchange))
                    .collectList()
                    .map(endpoints -> {
                        endpoints.sort(Comparator.comparing(SpringSecurityEndpointDto::pattern)
                                .thenComparing(SpringSecurityEndpointDto::method));
                        return new SpringSecurityEndpointsReport(
                                springSecurityPresent, true, endpoints.size(), endpoints);
                    });
        });
    }

    private Mono<SpringSecurityExplainDto> explain(List<ObservedChain> chains, ExplainExchange exchange) {
        if (chains.isEmpty()) {
            return Mono.just(new SpringSecurityExplainDto(false, false, null, null, List.of()));
        }
        return Flux.fromIterable(chains)
                .concatMap(chain ->
                        evaluateMatch(chain, exchange).map(evaluation -> new EvaluatedChain(chain, evaluation)))
                .filter(evaluated -> evaluated.evaluation().matched()
                        || evaluated.evaluation().errorType() != null)
                .next()
                .map(evaluated -> {
                    MatchEvaluation evaluation = evaluated.evaluation();
                    ObservedChain chain = evaluated.chain();
                    if (evaluation.errorType() != null) {
                        return new SpringSecurityExplainDto(
                                false,
                                true,
                                null,
                                "Chain " + chain.index() + " matcher threw " + evaluation.errorType()
                                        + " - requires more request context than available",
                                List.of());
                    }
                    return new SpringSecurityExplainDto(
                            true,
                            exchange.isBestEffort(),
                            chain.index(),
                            chain.matcher().description(),
                            filterNames(chain.filters()));
                })
                .defaultIfEmpty(new SpringSecurityExplainDto(
                        false, exchange.isBestEffort(), null, "No chain matched", List.of()));
    }

    private List<EndpointCandidate> describeEndpoints(RequestMappingInfo info, HandlerMethod handlerMethod) {
        Set<String> patterns = info.getPatternsCondition().getPatterns().stream()
                .map(org.springframework.web.util.pattern.PathPattern::getPatternString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (patterns.isEmpty()) {
            patterns.add("/**");
        }
        Set<String> methods = info.getMethodsCondition().getMethods().stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (methods.isEmpty()) {
            methods.add("ANY");
        }
        String handler = handlerMethod.getBeanType().getSimpleName() + "#"
                + handlerMethod.getMethod().getName();

        List<EndpointCandidate> result = new ArrayList<>();
        for (String pattern : patterns) {
            if (!selfDataFilter.shouldIncludeSecurityEndpoint(List.of(pattern), handler)) {
                continue;
            }
            for (String method : methods) {
                RepresentativePath representative = representativePath(pattern);
                result.add(new EndpointCandidate(
                        method, pattern, representative.path(), handler, representative.bestEffort()));
            }
        }
        return result;
    }

    private Mono<SpringSecurityEndpointDto> resolveEndpoint(
            EndpointCandidate endpoint, List<ObservedChain> chains, ServerWebExchange templateExchange) {
        if (chains.isEmpty()) {
            return Mono.just(endpointDto(
                    endpoint,
                    false,
                    "unsecured",
                    List.of(),
                    null,
                    null,
                    "No Spring Security filter chains configured",
                    endpoint.bestEffort()));
        }

        ExplainExchange exchange =
                new ExplainExchange(templateExchange, endpoint.method(), endpoint.representativePath(), true);
        return Flux.fromIterable(chains)
                .concatMap(chain ->
                        evaluateMatch(chain, exchange).map(evaluation -> new EvaluatedChain(chain, evaluation)))
                .filter(evaluated -> evaluated.evaluation().matched()
                        || evaluated.evaluation().errorType() != null)
                .next()
                .flatMap(evaluated -> {
                    ObservedChain chain = evaluated.chain();
                    MatchEvaluation evaluation = evaluated.evaluation();
                    if (evaluation.errorType() != null) {
                        return Mono.just(endpointDto(
                                endpoint,
                                true,
                                "unknown",
                                List.of(),
                                chain.index(),
                                chain.matcher().description(),
                                "Chain matcher threw " + evaluation.errorType(),
                                true));
                    }
                    AuthorizationWebFilter authFilter = findAuthorizationFilter(chain);
                    if (authFilter == null) {
                        return Mono.just(endpointDto(
                                endpoint,
                                true,
                                "unknown",
                                List.of(),
                                chain.index(),
                                chain.matcher().description(),
                                "Chain has no AuthorizationWebFilter",
                                exchange.isBestEffort()));
                    }
                    ReactiveAuthorizationManager<ServerWebExchange> manager = authorizationManager(authFilter);
                    if (manager == null) {
                        return Mono.just(endpointDto(
                                endpoint,
                                true,
                                "unknown",
                                List.of(),
                                chain.index(),
                                chain.matcher().description(),
                                "Authorization manager metadata is not exposed by this Spring Security version",
                                true));
                    }
                    return classifyRule(endpoint, chain, manager, exchange);
                })
                .switchIfEmpty(Mono.just(endpointDto(
                        endpoint,
                        false,
                        "unsecured",
                        List.of(),
                        null,
                        null,
                        "No Spring Security filter chain matched",
                        exchange.isBestEffort())));
    }

    private Mono<SpringSecurityEndpointDto> classifyRule(
            EndpointCandidate endpoint,
            ObservedChain chain,
            ReactiveAuthorizationManager<ServerWebExchange> manager,
            ExplainExchange exchange) {
        return simulate(manager, anonymousAuth(), exchange).flatMap(anonymous -> {
            if (anonymous.errorType() != null) {
                return Mono.just(unknownRule(endpoint, chain, manager, anonymous.errorType()));
            }
            if (Boolean.TRUE.equals(anonymous.granted())) {
                return Mono.just(endpointDto(
                        endpoint,
                        true,
                        "permitAll",
                        List.of(),
                        chain.index(),
                        chain.matcher().description(),
                        null,
                        exchange.isBestEffort()));
            }
            return simulate(manager, authenticatedAuth(List.of()), exchange).flatMap(authenticated -> {
                if (authenticated.errorType() != null) {
                    return Mono.just(unknownRule(endpoint, chain, manager, authenticated.errorType()));
                }
                if (Boolean.TRUE.equals(authenticated.granted())) {
                    return Mono.just(endpointDto(
                            endpoint,
                            true,
                            "authenticated",
                            List.of(),
                            chain.index(),
                            chain.matcher().description(),
                            null,
                            exchange.isBestEffort()));
                }
                return classifyAuthorities(endpoint, chain, manager, exchange);
            });
        });
    }

    private Mono<SpringSecurityEndpointDto> classifyAuthorities(
            EndpointCandidate endpoint,
            ObservedChain chain,
            ReactiveAuthorizationManager<ServerWebExchange> manager,
            ExplainExchange exchange) {
        AuthoritySpec spec = extractAuthorities(manager);
        if (spec == null || spec.authorities().isEmpty()) {
            return Mono.just(endpointDto(
                    endpoint,
                    true,
                    "custom",
                    List.of(),
                    chain.index(),
                    chain.matcher().description(),
                    "Managed by " + typeName(manager),
                    exchange.isBestEffort()));
        }
        return simulate(manager, authenticatedAuth(spec.authorities()), exchange)
                .map(authorityDecision -> {
                    if (authorityDecision.errorType() != null) {
                        return unknownRule(endpoint, chain, manager, authorityDecision.errorType());
                    }
                    if (!Boolean.TRUE.equals(authorityDecision.granted())) {
                        return endpointDto(
                                endpoint,
                                true,
                                "custom",
                                List.of(),
                                chain.index(),
                                chain.matcher().description(),
                                "Managed by " + typeName(manager),
                                exchange.isBestEffort());
                    }
                    List<String> exposed = spec.authorities().stream()
                            .map(authority -> spec.allRolePrefixed() && authority.startsWith("ROLE_")
                                    ? authority.substring("ROLE_".length())
                                    : authority)
                            .toList();
                    return endpointDto(
                            endpoint,
                            true,
                            spec.allRolePrefixed() ? "hasRole" : "hasAuthority",
                            exposed,
                            chain.index(),
                            chain.matcher().description(),
                            null,
                            exchange.isBestEffort());
                });
    }

    private SpringSecurityEndpointDto unknownRule(
            EndpointCandidate endpoint,
            ObservedChain chain,
            ReactiveAuthorizationManager<ServerWebExchange> manager,
            String errorType) {
        return endpointDto(
                endpoint,
                true,
                "unknown",
                List.of(),
                chain.index(),
                chain.matcher().description(),
                "Authorization manager " + typeName(manager) + " threw " + errorType,
                true);
    }

    private Mono<Simulation> simulate(
            ReactiveAuthorizationManager<ServerWebExchange> manager,
            Authentication authentication,
            ExplainExchange exchange) {
        return manager.authorize(Mono.just(authentication), exchange)
                .map(result -> new Simulation(result.isGranted(), null))
                .defaultIfEmpty(new Simulation(null, "EmptyAuthorizationResult"))
                .onErrorResume(
                        error -> Mono.just(new Simulation(null, error.getClass().getSimpleName())));
    }

    private Mono<MatchEvaluation> evaluateMatch(ObservedChain chain, ExplainExchange exchange) {
        return chain.chain()
                .matches(exchange)
                .map(matched -> new MatchEvaluation(Boolean.TRUE.equals(matched), null))
                .defaultIfEmpty(new MatchEvaluation(false, null))
                .onErrorResume(error ->
                        Mono.just(new MatchEvaluation(false, error.getClass().getSimpleName())));
    }

    private Mono<List<ObservedChain>> observeApplicationChains() {
        List<IndexedChain> indexedChains = new ArrayList<>();
        List<SecurityWebFilterChain> chains =
                filterChainProvider.orderedStream().toList();
        for (int index = 0; index < chains.size(); index++) {
            SecurityWebFilterChain chain = chains.get(index);
            MatcherInfo matcher = matcherInfo(chain);
            if (selfDataFilter.shouldIncludeSecurityChain(matcher.description())) {
                indexedChains.add(new IndexedChain(index, chain, matcher));
            }
        }
        return Flux.fromIterable(indexedChains)
                .concatMap(indexed -> indexed.chain()
                        .getWebFilters()
                        .collectList()
                        .map(filters -> new ObservedChain(
                                indexed.index(), indexed.chain(), indexed.matcher(), List.copyOf(filters))))
                .collectList();
    }

    private SpringSecurityFilterChainDto toChainDto(ObservedChain chain) {
        List<WebFilter> filters = chain.filters();
        return new SpringSecurityFilterChainDto(
                chain.index(),
                chain.matcher().description(),
                chain.matcher().type(),
                filterNames(filters),
                hasFilter(filters, "CsrfWebFilter"),
                hasFilter(filters, "CorsWebFilter"),
                hasFilter(filters, "SecurityContextServerWebExchangeWebFilter")
                        || hasFilter(filters, "ReactorContextWebFilter"));
    }

    private MatcherInfo matcherInfo(SecurityWebFilterChain chain) {
        if (chain instanceof MatcherSecurityWebFilterChain) {
            Object value = readField(chain, "matcher");
            if (value instanceof ServerWebExchangeMatcher matcher) {
                return new MatcherInfo(describeMatcher(matcher, 0), typeName(matcher));
            }
        }
        return new MatcherInfo("(custom chain: " + typeName(chain) + ")", typeName(chain));
    }

    private String describeMatcher(ServerWebExchangeMatcher matcher, int depth) {
        if (depth >= 8) {
            return typeName(matcher);
        }
        String className = matcher.getClass().getName();
        String simpleName = matcher.getClass().getSimpleName();
        if ("OrServerWebExchangeMatcher".equals(simpleName) || "AndServerWebExchangeMatcher".equals(simpleName)) {
            Object nested = readField(matcher, "matchers");
            if (nested instanceof Collection<?> matchers) {
                String separator = "OrServerWebExchangeMatcher".equals(simpleName) ? " OR " : " AND ";
                List<String> descriptions = matchers.stream()
                        .filter(ServerWebExchangeMatcher.class::isInstance)
                        .map(ServerWebExchangeMatcher.class::cast)
                        .map(item -> describeMatcher(item, depth + 1))
                        .toList();
                if (!descriptions.isEmpty()) {
                    return "(" + String.join(separator, descriptions) + ")";
                }
            }
            return simpleName;
        }
        if ("NegatedServerWebExchangeMatcher".equals(simpleName)) {
            Object nested = readField(matcher, "matcher");
            if (nested instanceof ServerWebExchangeMatcher nestedMatcher) {
                return "NOT " + describeMatcher(nestedMatcher, depth + 1);
            }
            return simpleName;
        }
        if (className.startsWith(SPRING_MATCHER_PACKAGE)
                && ("PathPatternParserServerWebExchangeMatcher".equals(simpleName)
                        || "MediaTypeServerWebExchangeMatcher".equals(simpleName)
                        || "IpAddressServerWebExchangeMatcher".equals(simpleName))) {
            return String.valueOf(matcher);
        }
        return className.startsWith(SPRING_MATCHER_PACKAGE) ? simpleName : "(custom matcher: " + className + ")";
    }

    private AuthorizationWebFilter findAuthorizationFilter(ObservedChain chain) {
        return chain.filters().stream()
                .filter(AuthorizationWebFilter.class::isInstance)
                .map(AuthorizationWebFilter.class::cast)
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private ReactiveAuthorizationManager<ServerWebExchange> authorizationManager(AuthorizationWebFilter filter) {
        Object value = readField(filter, "authorizationManager");
        if (value instanceof ReactiveAuthorizationManager<?> manager) {
            return (ReactiveAuthorizationManager<ServerWebExchange>) manager;
        }
        return null;
    }

    private AuthoritySpec extractAuthorities(Object manager) {
        if (!manager.getClass().getName().startsWith("org.springframework.security.")) {
            return null;
        }
        try {
            Method method = manager.getClass().getMethod("getAuthorities");
            List<String> names = readAuthorityNames(method.invoke(manager));
            if (names != null && !names.isEmpty()) {
                return authoritySpec(names);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            log.debug("Authorization manager {} does not expose getAuthorities()", typeName(manager));
        }

        Matcher matcher = AUTHORITIES_LIST.matcher(String.valueOf(manager));
        if (!matcher.find()) {
            return null;
        }
        List<String> names = Pattern.compile(",")
                .splitAsStream(matcher.group(1))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
        return names.isEmpty() ? null : authoritySpec(names);
    }

    private AuthoritySpec authoritySpec(List<String> names) {
        return new AuthoritySpec(names, names.stream().allMatch(authority -> authority.startsWith("ROLE_")));
    }

    private List<String> readAuthorityNames(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return null;
        }
        List<String> names = new ArrayList<>(collection.size());
        for (Object element : collection) {
            if (element instanceof GrantedAuthority authority) {
                String name = authority.getAuthority();
                if (name != null) {
                    names.add(name);
                }
            } else if (element instanceof CharSequence text) {
                names.add(text.toString());
            }
        }
        return names;
    }

    private Authentication anonymousAuth() {
        return new AnonymousAuthenticationToken(
                "bootui-explain", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    private Authentication authenticatedAuth(List<String> authorities) {
        List<SimpleGrantedAuthority> granted =
                authorities.stream().map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated("bootui-explain", "not-exposed", granted);
    }

    private SpringSecurityAuthDto buildAuth() {
        List<String> providerTypes = authManagerProvider.stream()
                .map(manager -> manager.getClass().getName())
                .sorted()
                .toList();
        List<String> userDetailsTypes = userDetailsServiceProvider.stream()
                .map(service -> service.getClass().getName())
                .sorted()
                .toList();
        return new SpringSecurityAuthDto(providerTypes, userDetailsTypes, configuredUsername());
    }

    private String configuredUsername() {
        ValueExposure valueExposure = exposure.valueExposure();
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        String value = environment.getProperty(CONFIGURED_USERNAME_PROPERTY);
        if (value == null
                || valueExposure == ValueExposure.FULL
                || !exposure.maskSecrets()
                || !masker.shouldMask(CONFIGURED_USERNAME_PROPERTY, value)) {
            return value;
        }
        return SecretMasker.MASKED_VALUE;
    }

    private List<String> filterNames(List<WebFilter> filters) {
        return filters.stream().map(ReactiveSpringSecurityService::typeName).toList();
    }

    private boolean hasFilter(List<WebFilter> filters, String simpleClassName) {
        return filters.stream().anyMatch(filter -> typeName(filter).equals(simpleClassName));
    }

    private static String typeName(Object value) {
        String simpleName = value.getClass().getSimpleName();
        return simpleName.isBlank() ? value.getClass().getName() : simpleName;
    }

    private static Object readField(Object target, String fieldName) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                if (!field.trySetAccessible()) {
                    log.debug("Field {}.{} is not accessible", type.getName(), fieldName);
                    return null;
                }
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException | RuntimeException exception) {
                log.debug("Could not read field {}.{}", type.getName(), fieldName, exception);
                return null;
            }
        }
        return null;
    }

    private static RepresentativePath representativePath(String pattern) {
        StringBuilder result = new StringBuilder(pattern.length());
        boolean changed = false;
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (current == '{') {
                int depth = 1;
                while (++index < pattern.length() && depth > 0) {
                    char nested = pattern.charAt(index);
                    if (nested == '{') {
                        depth++;
                    } else if (nested == '}') {
                        depth--;
                    }
                }
                result.append("bootui");
                changed = true;
            } else if (current == '*') {
                if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '*') {
                    index++;
                    result.append("bootui/path");
                } else {
                    result.append("bootui");
                }
                changed = true;
            } else if (current == '?') {
                result.append('x');
                changed = true;
            } else {
                result.append(current);
            }
        }
        return new RepresentativePath(result.toString(), changed);
    }

    private SpringSecurityEndpointDto endpointDto(
            EndpointCandidate endpoint,
            boolean secured,
            String rule,
            List<String> roles,
            Integer chainIndex,
            String matcherDescription,
            String description,
            boolean bestEffort) {
        return new SpringSecurityEndpointDto(
                endpoint.method(),
                endpoint.pattern(),
                endpoint.handler(),
                secured,
                rule,
                roles,
                chainIndex,
                matcherDescription,
                description,
                bestEffort);
    }

    private record MatcherInfo(String description, String type) {}

    private record IndexedChain(int index, SecurityWebFilterChain chain, MatcherInfo matcher) {}

    private record ObservedChain(
            int index, SecurityWebFilterChain chain, MatcherInfo matcher, List<WebFilter> filters) {}

    private record MatchEvaluation(boolean matched, String errorType) {}

    private record EvaluatedChain(ObservedChain chain, MatchEvaluation evaluation) {}

    private record Simulation(Boolean granted, String errorType) {}

    private record AuthoritySpec(List<String> authorities, boolean allRolePrefixed) {}

    private record RepresentativePath(String path, boolean bestEffort) {}

    private record EndpointCandidate(
            String method, String pattern, String representativePath, String handler, boolean bestEffort) {}

    /**
     * A path/method-only exchange derived from the real request. It deliberately removes headers,
     * cookies, principal, session, body, and remote-address state; touching any of those channels
     * marks the result as best-effort rather than reusing potentially sensitive state from the
     * developer's BootUI request.
     */
    private static final class ExplainExchange extends ServerWebExchangeDecorator {

        private final AtomicBoolean bestEffort;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private final ExplainRequest request;
        private final String path;

        ExplainExchange(ServerWebExchange delegate, String method, String path, boolean initiallyBestEffort) {
            super(delegate);
            this.bestEffort = new AtomicBoolean(initiallyBestEffort);
            HttpMethod httpMethod = parseMethod(method, bestEffort);
            URI uri = parseUri(path);
            this.path = uri.getRawPath();
            this.request = new ExplainRequest(delegate.getRequest(), httpMethod, uri, bestEffort);
        }

        String path() {
            return path;
        }

        boolean isBestEffort() {
            return bestEffort.get();
        }

        @Override
        public ServerHttpRequest getRequest() {
            return request;
        }

        @Override
        public ServerHttpResponse getResponse() {
            bestEffort.set(true);
            return super.getResponse();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Mono<WebSession> getSession() {
            bestEffort.set(true);
            return Mono.empty();
        }

        @Override
        public <T extends Principal> Mono<T> getPrincipal() {
            bestEffort.set(true);
            return Mono.empty();
        }

        @Override
        public Mono<MultiValueMap<String, String>> getFormData() {
            bestEffort.set(true);
            return Mono.just(new LinkedMultiValueMap<>());
        }

        @Override
        public Mono<MultiValueMap<String, Part>> getMultipartData() {
            bestEffort.set(true);
            return Mono.just(new LinkedMultiValueMap<>());
        }

        private static HttpMethod parseMethod(String method, AtomicBoolean bestEffort) {
            if (method == null || method.isBlank()) {
                return HttpMethod.GET;
            }
            if ("ANY".equalsIgnoreCase(method)) {
                bestEffort.set(true);
                return HttpMethod.GET;
            }
            String normalized = method.toUpperCase(Locale.ROOT);
            if (!HTTP_METHOD_TOKEN.matcher(normalized).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported HTTP method: " + method);
            }
            try {
                return HttpMethod.valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported HTTP method: " + method);
            }
        }

        private static URI parseUri(String path) {
            String normalized = path == null || path.isBlank() ? "/" : (path.startsWith("/") ? path : "/" + path);
            if (normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('#') >= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path must be an HTTP request path");
            }
            try {
                URI uri = URI.create("http://localhost" + normalized);
                if (uri.getRawPath() == null || !uri.getRawPath().startsWith("/")) {
                    throw new IllegalArgumentException("missing absolute path");
                }
                return uri;
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid HTTP request path", exception);
            }
        }
    }

    private static final class ExplainRequest extends ServerHttpRequestDecorator {

        private final HttpMethod method;
        private final URI uri;
        private final RequestPath path;
        private final AtomicBoolean bestEffort;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        ExplainRequest(ServerHttpRequest delegate, HttpMethod method, URI uri, AtomicBoolean bestEffort) {
            super(delegate);
            this.method = method;
            this.uri = uri;
            this.path = RequestPath.parse(uri, "");
            this.bestEffort = bestEffort;
        }

        @Override
        public String getId() {
            return "bootui-explain";
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
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public RequestPath getPath() {
            return path;
        }

        @Override
        public MultiValueMap<String, String> getQueryParams() {
            bestEffort.set(true);
            return new LinkedMultiValueMap<>();
        }

        @Override
        public HttpHeaders getHeaders() {
            bestEffort.set(true);
            return HttpHeaders.EMPTY;
        }

        @Override
        public MultiValueMap<String, HttpCookie> getCookies() {
            bestEffort.set(true);
            return new LinkedMultiValueMap<>();
        }

        @Override
        public java.net.InetSocketAddress getLocalAddress() {
            bestEffort.set(true);
            return null;
        }

        @Override
        public java.net.InetSocketAddress getRemoteAddress() {
            bestEffort.set(true);
            return null;
        }

        @Override
        public org.springframework.http.server.reactive.SslInfo getSslInfo() {
            bestEffort.set(true);
            return null;
        }

        @Override
        public Flux<org.springframework.core.io.buffer.DataBuffer> getBody() {
            bestEffort.set(true);
            return Flux.empty();
        }
    }
}
