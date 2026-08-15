package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorDefaultsEnvironmentPostProcessor;
import io.github.jdubois.bootui.engine.reactivesecurity.CorsConfigObservation;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityEnvironmentSnapshot;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityObservation;
import io.github.jdubois.bootui.engine.reactivesecurity.WebFilterChainObservation;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.web.server.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.server.WebFilter;

/**
 * Collects a framework-neutral {@link ReactiveSecurityObservation} from the application's registered
 * {@code SecurityWebFilterChain} beans, CORS beans, and {@code Environment} for the reactive Spring
 * Security advisor engine.
 *
 * <p>This class owns every piece of Spring-specific collection the advisor needs: bean lookups,
 * reflection into Spring Security's internal filter/header-writer fields, and {@code Environment} /
 * {@code PropertySource} reading (including {@link #suspectedHardcodedSecretKeys()}, which reports
 * property <em>keys</em> only — the matched values are never read into the observation). It prefers
 * the application's own registered {@code SecurityWebFilterChain} beans (excluding BootUI's own
 * {@code bootUiReactiveSecurityWebFilterChain}, mirroring {@code PanelsController}'s availability
 * check) over reflecting into {@code WebFilterChainProxy}.</p>
 *
 * <p>{@link #collect()} may block briefly (bounded by {@link #FILTER_COLLECT_TIMEOUT}) when a chain's
 * filters cannot be read via the reflection fast path, so it must only ever be invoked from a
 * non-event-loop thread (the controller runs the whole scan on {@code Schedulers.boundedElastic()}).</p>
 */
public final class SpringReactiveSecurityObservationCollector {

    private static final String BOOT_UI_CHAIN_BEAN_NAME = "bootUiReactiveSecurityWebFilterChain";
    private static final Duration FILTER_COLLECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_HEADER_WRITER_DEPTH = 8;
    private static final String SPRING_MATCHER_PACKAGE = "org.springframework.security.web.server.util.matcher.";

    private static final Pattern SUSPECTED_SECRET_KEY = Pattern.compile(
            ".*(password|passwd|secret|token|api-?key|client-secret|private-key).*", Pattern.CASE_INSENSITIVE);

    private static final Pattern NON_SECRET_VALUE_KEY_SUFFIX = Pattern.compile(
            ".*[.-](expiration|expiry|expires|ttl|timeout|duration|validity|max-age|maxage|refresh-interval)$",
            Pattern.CASE_INSENSITIVE);

    private final ObjectProvider<SecurityWebFilterChain> filterChainProvider;
    private final ObjectProvider<ListableBeanFactory> beanFactories;
    private final Environment environment;

    public SpringReactiveSecurityObservationCollector(
            ObjectProvider<SecurityWebFilterChain> filterChainProvider,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment) {
        this.filterChainProvider = filterChainProvider;
        this.beanFactories = beanFactories;
        this.environment = environment;
    }

    /**
     * Collects the current observation. May block briefly (bounded) while extracting a chain's
     * filters; callers must run this off any reactive event-loop thread.
     */
    public ReactiveSecurityObservation collect() {
        List<String> errors = new ArrayList<>();
        List<SecurityWebFilterChain> applicationChains = applicationOwnedChains();

        List<WebFilterChainObservation> chains = new ArrayList<>();
        for (int i = 0; i < applicationChains.size(); i++) {
            try {
                chains.add(toChainObservation(i, applicationChains.get(i), errors));
            } catch (RuntimeException | LinkageError ex) {
                errors.add("Chain " + i + ": " + safeMessage(ex));
            }
        }

        ListableBeanFactory beanFactory = beanFactories.getIfAvailable();
        List<CorsConfigObservation> corsConfigs = new ArrayList<>();
        CorsDiscovery corsDiscovery = discoverCors(beanFactory, corsConfigs, errors);

        return new ReactiveSecurityObservation(
                chains,
                corsConfigs,
                corsDiscovery.sourcePresent(),
                List.of(),
                List.of(),
                List.of(),
                environmentSnapshot(),
                errors,
                corsDiscovery.complete());
    }

    // ── Application chain discovery (bean-name exclusion, not WebFilterChainProxy reflection) ──────

    private List<SecurityWebFilterChain> applicationOwnedChains() {
        List<SecurityWebFilterChain> orderedChains =
                filterChainProvider.orderedStream().toList();
        SecurityWebFilterChain bootUiChain = resolveBootUiChain();
        if (bootUiChain == null) {
            return orderedChains;
        }
        List<SecurityWebFilterChain> withoutBootUi = new ArrayList<>();
        for (SecurityWebFilterChain chain : orderedChains) {
            if (chain != bootUiChain) {
                withoutBootUi.add(chain);
            }
        }
        return withoutBootUi;
    }

    private SecurityWebFilterChain resolveBootUiChain() {
        ListableBeanFactory beanFactory = beanFactories.getIfAvailable();
        if (beanFactory == null) {
            return null;
        }
        try {
            return beanFactory.getBean(BOOT_UI_CHAIN_BEAN_NAME, SecurityWebFilterChain.class);
        } catch (NoSuchBeanDefinitionException ex) {
            return null;
        }
    }

    private static WebFilterChainObservation toChainObservation(
            int index, SecurityWebFilterChain chain, List<String> errors) {
        String matcher = matcherDescription(chain);
        List<WebFilter> webFilters = extractFilters(chain);
        List<String> webFilterNames = webFilters == null
                ? List.of()
                : webFilters.stream()
                        .map(filter -> filter.getClass().getSimpleName())
                        .toList();
        Boolean permitsAllAnonymous = webFilters == null ? null : !webFilterNames.contains("AuthorizationWebFilter");
        if (webFilters == null) {
            errors.add("Chain " + index + ": web filters could not be collected");
        }
        boolean bearerTokenAuthentication = webFilters != null
                && webFilters.stream().anyMatch(SpringReactiveSecurityObservationCollector::isBearerTokenFilter);
        boolean formLoginAuthentication = webFilters != null
                && webFilters.stream().anyMatch(SpringReactiveSecurityObservationCollector::isFormLoginFilter);
        HeaderWriterInfo headerWriters = detectHeaderWriters(webFilters, index, errors);
        return new WebFilterChainObservation(
                index,
                matcher,
                webFilterNames,
                permitsAllAnonymous,
                bearerTokenAuthentication,
                headerWriters.names(),
                headerWriters.hstsMaxAgeSeconds(),
                headerWriters.hstsIncludeSubdomains(),
                headerWriters.cspPolicyDirectives(),
                headerWriters.cspReportOnly(),
                headerWriters.observed(),
                formLoginAuthentication);
    }

    /**
     * Safe, depth-bounded, recursive matcher description: unwraps Or/And/Negated matchers and only
     * calls raw {@code toString()} on a small allow-list of known-safe Spring Security matcher types,
     * falling back to the matcher's class name for anything else so no unexpected sensitive matcher
     * state (e.g. a lambda-captured credential) is ever surfaced.
     */
    private static String matcherDescription(SecurityWebFilterChain chain) {
        if (chain instanceof MatcherSecurityWebFilterChain) {
            Object value = readField(chain, "matcher");
            if (value instanceof ServerWebExchangeMatcher matcher) {
                return describeMatcher(matcher, 0);
            }
        }
        return "(custom chain: " + chain.getClass().getSimpleName() + ")";
    }

    private static String describeMatcher(ServerWebExchangeMatcher matcher, int depth) {
        if (matcher.getClass() == ServerWebExchangeMatchers.anyExchange().getClass()) {
            return "any request";
        }
        if (depth >= 8) {
            return matcher.getClass().getSimpleName();
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

    private static List<WebFilter> extractFilters(SecurityWebFilterChain chain) {
        // Fast path: reflect the "filters" List<WebFilter> field directly from the chain.
        Object raw = readField(chain, "filters");
        if (raw instanceof List<?> list) {
            List<WebFilter> filters = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof WebFilter webFilter) {
                    filters.add(webFilter);
                }
            }
            return filters;
        }
        // Fallback: subscribe with a short, bounded timeout. Never called on the event loop: this
        // collector is invoked only from the controller's boundedElastic-scheduled scan().
        try {
            return chain.getWebFilters().collectList().block(FILTER_COLLECT_TIMEOUT);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    // ── Header writer extraction ───────────────────────────────────────────────────

    private record HeaderWriterInfo(
            List<String> names,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives,
            Boolean cspReportOnly,
            boolean observed) {}

    private record FlattenedWriters(List<Object> writers, boolean complete) {}

    private static boolean isBearerTokenFilter(WebFilter filter) {
        if (!"AuthenticationWebFilter".equals(filter.getClass().getSimpleName())) {
            return false;
        }
        Object converter = readField(filter, "authenticationConverter");
        return converter != null && converter.getClass().getName().endsWith("ServerBearerTokenAuthenticationConverter");
    }

    private static boolean isFormLoginFilter(WebFilter filter) {
        if (!"AuthenticationWebFilter".equals(filter.getClass().getSimpleName())) {
            return false;
        }
        Object converter = readField(filter, "authenticationConverter");
        return converter != null && converter.getClass().getName().endsWith("ServerFormLoginAuthenticationConverter");
    }

    private static HeaderWriterInfo detectHeaderWriters(List<WebFilter> filters, int chainIndex, List<String> errors) {
        if (filters == null) {
            return new HeaderWriterInfo(List.of(), null, null, null, null, false);
        }
        for (Object filter : filters) {
            if (filter == null
                    || !"HttpHeaderWriterWebFilter".equals(filter.getClass().getSimpleName())) {
                continue;
            }
            return readHeaderWriterFilter(filter, chainIndex, errors);
        }
        return new HeaderWriterInfo(List.of(), null, null, null, null, true);
    }

    private static HeaderWriterInfo readHeaderWriterFilter(Object headerFilter, int chainIndex, List<String> errors) {
        Object writerField = readField(headerFilter, "writer");
        if (writerField == null) {
            errors.add("Chain " + chainIndex + ": header writers could not be collected");
            return new HeaderWriterInfo(List.of(), null, null, null, null, false);
        }
        FlattenedWriters flattened = flattenWriters(writerField);
        List<Object> writers = flattened.writers();
        if (!flattened.complete()) {
            errors.add("Chain " + chainIndex + ": header writers could not be fully collected");
        }
        List<String> names =
                writers.stream().map(w -> w.getClass().getSimpleName()).toList();
        Long hstsMaxAge = null;
        Boolean hstsIncludeSubdomains = null;
        String cspDirectives = null;
        Boolean cspReportOnly = null;
        for (Object writer : writers) {
            String simpleName = writer.getClass().getSimpleName();
            if (simpleName.contains("Hsts") || simpleName.contains("StrictTransportSecurity")) {
                hstsMaxAge = parseHstsMaxAge(readField(writer, "maxAge"));
                hstsIncludeSubdomains = parseHstsIncludeSubdomains(readField(writer, "subdomain"));
            } else if (simpleName.contains("ContentSecurityPolicy")) {
                Object policy = readField(writer, "policyDirectives");
                cspDirectives = policy == null ? null : String.valueOf(policy);
                Object reportOnly = readField(writer, "reportOnly");
                if (reportOnly instanceof Boolean b) {
                    cspReportOnly = b;
                }
            }
        }
        return new HeaderWriterInfo(
                names, hstsMaxAge, hstsIncludeSubdomains, cspDirectives, cspReportOnly, flattened.complete());
    }

    private static FlattenedWriters flattenWriters(Object writerField) {
        return flattenWriters(writerField, 0);
    }

    private static FlattenedWriters flattenWriters(Object writerField, int depth) {
        if (depth >= MAX_HEADER_WRITER_DEPTH) {
            return new FlattenedWriters(List.of(writerField), false);
        }
        Object delegateList = readField(writerField, "writers");
        if (delegateList instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            boolean complete = true;
            for (Object item : list) {
                if (item != null) {
                    FlattenedWriters nested = flattenWriters(item, depth + 1);
                    result.addAll(nested.writers());
                    complete &= nested.complete();
                }
            }
            return new FlattenedWriters(result, complete);
        }
        if (writerField.getClass().getSimpleName().contains("Composite")) {
            return new FlattenedWriters(List.of(), false);
        }
        return new FlattenedWriters(List.of(writerField), true);
    }

    private static Long parseHstsMaxAge(Object maxAge) {
        if (maxAge instanceof Number number) {
            return number.longValue();
        }
        if (maxAge instanceof String value) {
            int separator = value.indexOf('=');
            String seconds = separator >= 0 ? value.substring(separator + 1).trim() : value.trim();
            try {
                return Long.parseLong(seconds);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static Boolean parseHstsIncludeSubdomains(Object subdomain) {
        if (subdomain instanceof Boolean value) {
            return value;
        }
        if (subdomain instanceof String value) {
            return value.toLowerCase(Locale.ROOT).contains("includesubdomains");
        }
        return null;
    }

    // ── CORS discovery ────────────────────────────────────────────────────────────

    private record CorsDiscovery(boolean sourcePresent, boolean complete) {}

    private static CorsDiscovery discoverCors(
            ListableBeanFactory beanFactory, List<CorsConfigObservation> corsConfigs, List<String> errors) {
        if (beanFactory == null) {
            return new CorsDiscovery(false, true);
        }
        Class<?> sourceType = classForName("org.springframework.web.cors.reactive.CorsConfigurationSource");
        if (sourceType == null) {
            return new CorsDiscovery(false, true);
        }
        Map<String, ?> beans;
        try {
            beans = beanFactory.getBeansOfType(sourceType);
        } catch (RuntimeException | LinkageError ex) {
            errors.add("CORS: " + safeMessage(ex));
            return new CorsDiscovery(false, false);
        }
        boolean complete = true;
        for (Map.Entry<String, ?> beanEntry : beans.entrySet()) {
            Object bean = beanEntry.getValue();
            if (bean == null) {
                continue;
            }
            Object corsConfigurationsField = readField(bean, "corsConfigurations");
            if (corsConfigurationsField instanceof Map<?, ?> corsMap) {
                for (Map.Entry<?, ?> entry : corsMap.entrySet()) {
                    CorsConfigObservation observation =
                            toCorsObservation(String.valueOf(entry.getKey()), entry.getValue());
                    if (observation != null) {
                        corsConfigs.add(observation);
                    } else {
                        complete = false;
                        errors.add("CORS: configuration entry for " + entry.getKey() + " could not be inspected");
                    }
                }
            } else {
                complete = false;
                errors.add("CORS: configuration source '"
                        + beanEntry.getKey()
                        + "' ("
                        + bean.getClass().getName()
                        + ") could not be inspected");
            }
        }
        return new CorsDiscovery(!beans.isEmpty(), complete);
    }

    private static CorsConfigObservation toCorsObservation(String pattern, Object config) {
        if (!(config instanceof CorsConfiguration cors)) {
            return null;
        }
        return new CorsConfigObservation(
                pattern,
                nullableList(cors.getAllowedOrigins()),
                nullableList(cors.getAllowedOriginPatterns()),
                nullableList(cors.getAllowedMethods()),
                nullableList(cors.getAllowedHeaders()),
                cors.getAllowCredentials());
    }

    private static List<String> nullableList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    // ── Reflection helpers ────────────────────────────────────────────────────────

    private static Object readField(Object target, String fieldName) {
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ex) {
                current = current.getSuperclass();
            } catch (RuntimeException | LinkageError | IllegalAccessException ex) {
                return null;
            }
        }
        return null;
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    private static String safeMessage(Throwable ex) {
        return ex.getClass().getName();
    }

    // ── Environment snapshot (property values never leave this method; secrets report keys only) ──

    private ReactiveSecurityEnvironmentSnapshot environmentSnapshot() {
        List<String> profiles;
        try {
            profiles = List.of(environment.getActiveProfiles());
        } catch (RuntimeException ex) {
            profiles = List.of();
        }
        String issuerUri = firstProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        String jwkSetUri = firstProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        String publicKeyLocation = firstProperty("spring.security.oauth2.resourceserver.jwt.public-key-location");
        String introspectionUri = firstProperty("spring.security.oauth2.resourceserver.opaquetoken.introspection-uri");
        boolean staticPublicKeyConfigured = hasText(publicKeyLocation) && !hasText(issuerUri) && !hasText(jwkSetUri);
        String loggingLevel = firstProperty(
                "logging.level.org.springframework.security", "logging.level.org.springframework.security.web");
        return new ReactiveSecurityEnvironmentSnapshot(
                isGlobalTlsConfigured(),
                firstHostProperty("management.endpoints.web.exposure.include"),
                firstHostProperty("management.endpoints.web.exposure.exclude"),
                firstHostProperty("management.server.port") != null,
                profiles,
                false,
                staticPublicKeyConfigured,
                usesPlainHttp(issuerUri),
                usesPlainHttp(jwkSetUri),
                loggingLevel,
                suspectedHardcodedSecretKeys(),
                usesPlainHttp(introspectionUri),
                "always".equalsIgnoreCase(firstHostProperty("management.endpoint.env.show-values")),
                "always".equalsIgnoreCase(firstHostProperty("management.endpoint.configprops.show-values")),
                isActuatorEndpointWebExposed("env"),
                isActuatorEndpointWebExposed("configprops"));
    }

    private static boolean usesPlainHttp(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith("http://");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isGlobalTlsConfigured() {
        if (isPropertyTrue("server.ssl.enabled")
                || firstProperty("server.ssl.key-store") != null
                || firstProperty("server.ssl.bundle") != null
                || firstProperty("server.ssl.certificate") != null) {
            return true;
        }
        String forwarded = firstProperty("server.forward-headers-strategy");
        return forwarded != null && ("framework".equalsIgnoreCase(forwarded) || "native".equalsIgnoreCase(forwarded));
    }

    private boolean isActuatorEndpointWebExposed(String endpointId) {
        String include = firstProperty("management.endpoints.web.exposure.include");
        if (!containsCommaSeparated(include, endpointId) && !containsCommaSeparated(include, "*")) {
            return false;
        }
        String exclude = firstProperty("management.endpoints.web.exposure.exclude");
        if (containsCommaSeparated(exclude, endpointId) || containsCommaSeparated(exclude, "*")) {
            return false;
        }
        String endpointPrefix = "management.endpoint." + endpointId;
        String endpointAccess = firstProperty(endpointPrefix + ".access");
        if (isPropertyFalse(endpointPrefix + ".enabled")
                || "none".equalsIgnoreCase(endpointAccess)
                || "none".equalsIgnoreCase(firstProperty("management.endpoints.access.max-permitted"))) {
            return false;
        }
        if (endpointAccess == null && "none".equalsIgnoreCase(firstProperty("management.endpoints.access.default"))) {
            return false;
        }
        return !isPropertyFalse("management.endpoints.enabled-by-default")
                || firstProperty(endpointPrefix + ".enabled") != null
                || endpointAccess != null;
    }

    private static boolean containsCommaSeparated(String value, String candidate) {
        if (value == null) {
            return false;
        }
        for (String token : value.split(",")) {
            if (candidate.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private String firstProperty(String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isPropertyTrue(String... keys) {
        String value = firstProperty(keys);
        return value != null && "true".equalsIgnoreCase(value);
    }

    private boolean isPropertyFalse(String... keys) {
        String value = firstProperty(keys);
        return value != null && "false".equalsIgnoreCase(value);
    }

    private String firstHostProperty(String... keys) {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return firstProperty(keys);
        }
        for (String key : keys) {
            String value = hostProperty(configurableEnvironment, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String hostProperty(ConfigurableEnvironment configurableEnvironment, String key) {
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource)) {
                continue;
            }
            Object value = propertySource.getProperty(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (text.isBlank()) {
                continue;
            }
            if (isBootUiActuatorDefault(propertySource, key, text)) {
                continue;
            }
            return text;
        }
        return null;
    }

    private boolean isBootUiActuatorDefault(PropertySource<?> propertySource, String key, String value) {
        return DefaultPropertiesPropertySource.NAME.equals(propertySource.getName())
                && BootUiActuatorDefaultsEnvironmentPostProcessor.isBootUiActuatorDefault(key, value);
    }

    /**
     * Property <em>keys</em> (never values) whose names suggest a credential or secret and whose
     * values appear to be literal strings rather than placeholder references.
     */
    private Set<String> suspectedHardcodedSecretKeys() {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return Set.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (!isScannableConfigSource(propertySource)) {
                continue;
            }
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (name == null
                        || name.isBlank()
                        || name.toLowerCase(Locale.ROOT).startsWith("bootui.")) {
                    continue;
                }
                if (!SUSPECTED_SECRET_KEY.matcher(name).matches()
                        || NON_SECRET_VALUE_KEY_SUFFIX.matcher(name).matches()) {
                    continue;
                }
                Object rawValue = propertySource.getProperty(name);
                if (!(rawValue instanceof String text) || text.isBlank() || text.contains("${")) {
                    continue;
                }
                found.add(name);
            }
        }
        return found;
    }

    private static boolean isScannableConfigSource(PropertySource<?> propertySource) {
        if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource)) {
            return false;
        }
        String name = propertySource.getName();
        if (StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(name)
                || StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(name)
                || RandomValuePropertySource.RANDOM_PROPERTY_SOURCE_NAME.equals(name)
                || DefaultPropertiesPropertySource.NAME.equals(name)) {
            return false;
        }
        return !(propertySource instanceof ConfigTreePropertySource);
    }
}
