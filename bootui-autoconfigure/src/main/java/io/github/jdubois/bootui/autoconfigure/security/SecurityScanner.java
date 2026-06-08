package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.PasswordEncoderModel;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.core.dto.SecurityScanStatusDto;
import io.github.jdubois.bootui.core.dto.SecuritySeverityCountDto;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Bounded, on-demand Spring Security advisor.
 *
 * <p>The scanner reads the registered {@code SecurityFilterChain} beans and related security beans,
 * builds a read-only model, and runs a curated registry of static best-practice checks. It never
 * intercepts live requests beyond simulating an anonymous authorization decision against an in-memory
 * stub, and never surfaces credentials, keys, or session identifiers.</p>
 */
final class SecurityScanner {

    private static final String ANALYZER = "BootUI Spring Security Advisor";
    private static final String DISCLAIMER =
            "Heuristic Spring Security rules run against the host application's registered filter chains "
                    + "and security beans only. These checks are review prompts, not verdicts, and should be "
                    + "validated against the application's threat model.";
    private static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW", "INFO");
    private static final int MAX_BEAN_SCAN = 5000;

    private static final Comparator<SecurityRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (SecurityRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(Comparator.comparingInt(SecurityRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(SecurityRuleResultDto::id);

    private final Supplier<SecurityDiscovery> discoverySupplier;
    private final Environment environment;
    private final Clock clock;

    SecurityScanner(
            ObjectProvider<FilterChainProxy> filterChainProxies,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment,
            Clock clock) {
        this(() -> discover(filterChainProxies, beanFactories, environment), environment, clock);
    }

    SecurityScanner(SecurityContext context, Clock clock) {
        this(() -> new SecurityDiscovery(context, List.of()), context.environment(), clock);
    }

    private SecurityScanner(Supplier<SecurityDiscovery> discoverySupplier, Environment environment, Clock clock) {
        this.discoverySupplier = discoverySupplier;
        this.environment = environment;
        this.clock = clock;
    }

    SecurityReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Security Advisor has not run yet. Click Run security checks to inspect the filter chains.",
                null,
                0,
                0,
                List.of());
    }

    SecurityReport scan() {
        SecurityDiscovery discovery = safeDiscovery();
        SecurityContext context = discovery.context();
        if (context == null) {
            String message = discovery.errors().isEmpty()
                    ? "No Spring Security FilterChainProxy was found to inspect."
                    : "Spring Security configuration could not be read: " + String.join("; ", discovery.errors());
            return report("DISABLED", message, clock.millis(), 0, 0, List.of());
        }

        List<SecurityRuleResultDto> results = SecurityRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        int chains = context.chains().size();
        String status = discovery.errors().isEmpty() ? "SCANNED" : "PARTIAL";
        String message = "Security Advisor completed against " + chains + " filter chain" + (chains == 1 ? "." : "s.");
        if (!discovery.errors().isEmpty()) {
            message += " Some configuration could not be read: " + String.join("; ", discovery.errors());
        }
        return report(status, message, clock.millis(), chains, results.size(), results);
    }

    private SecurityReport report(
            String status,
            String message,
            Long scannedAt,
            int filterChainsAnalyzed,
            int rulesEvaluated,
            List<SecurityRuleResultDto> results) {
        List<SecurityRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        SecurityScanStatusDto scan = new SecurityScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, filterChainsAnalyzed, violationsFound);
        return new SecurityReport(
                true,
                DISCLAIMER,
                chainDescriptions(lastContext),
                filterChainsAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(violations),
                scan,
                violations);
    }

    // The most recent context, captured so the report can list chain matchers.
    private volatile SecurityContext lastContext;

    SecurityReport applyDismissals(SecurityReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<SecurityRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<SecurityRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        SecurityScanStatusDto scan = report.scan();
        SecurityScanStatusDto updatedScan = new SecurityScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                scan.filterChainsAnalyzed(),
                violationsFound);
        return new SecurityReport(
                report.localOnly(),
                report.disclaimer(),
                report.filterChains(),
                report.filterChainsAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                severityCounts(active),
                updatedScan,
                marked);
    }

    private SecurityDiscovery safeDiscovery() {
        try {
            SecurityDiscovery discovery = discoverySupplier.get();
            if (discovery == null) {
                return SecurityDiscovery.empty("No Spring Security FilterChainProxy is available.");
            }
            lastContext = discovery.context();
            return discovery;
        } catch (RuntimeException | LinkageError ex) {
            return SecurityDiscovery.empty(safeMessage(ex));
        }
    }

    private static List<String> chainDescriptions(SecurityContext context) {
        if (context == null) {
            return List.of();
        }
        return context.chains().stream().map(FilterChainModel::matcher).toList();
    }

    // ── Discovery ──────────────────────────────────────────────────────────────

    private static SecurityDiscovery discover(
            ObjectProvider<FilterChainProxy> filterChainProxies,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment) {
        FilterChainProxy proxy;
        try {
            proxy = filterChainProxies.getIfAvailable();
        } catch (RuntimeException | LinkageError ex) {
            return SecurityDiscovery.empty(safeMessage(ex));
        }
        if (proxy == null) {
            return SecurityDiscovery.empty("No Spring Security FilterChainProxy is available.");
        }

        List<String> errors = new ArrayList<>();
        List<FilterChainModel> chains = new ArrayList<>();
        try {
            List<SecurityFilterChain> securityChains = proxy.getFilterChains();
            for (int i = 0; i < securityChains.size(); i++) {
                try {
                    chains.add(toChainModel(i, securityChains.get(i)));
                } catch (RuntimeException | LinkageError ex) {
                    errors.add("Chain " + i + ": " + safeMessage(ex));
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            errors.add("Filter chains: " + safeMessage(ex));
        }

        ListableBeanFactory beanFactory = beanFactories.getIfAvailable();
        List<PasswordEncoderModel> passwordEncoders = discoverPasswordEncoders(beanFactory);
        List<String> jwtDecoderTypes = beanTypeNames(beanFactory, "org.springframework.security.oauth2.jwt.JwtDecoder");
        List<CorsConfigModel> corsConfigs = new ArrayList<>();
        boolean corsSourcePresent = discoverCors(beanFactory, corsConfigs, errors);
        boolean methodSecurityEnabled = !beanTypeNames(
                                beanFactory,
                                "org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor")
                        .isEmpty()
                || !beanTypeNames(
                                beanFactory,
                                "org.springframework.security.access.intercept.aopalliance.MethodSecurityInterceptor")
                        .isEmpty();
        boolean globalMethodSecurityLegacy = !beanTypeNames(
                        beanFactory,
                        "org.springframework.security.config.annotation.method.configuration.GlobalMethodSecurityConfiguration")
                .isEmpty();
        boolean webSecurityConfigurerAdapter = !beanTypeNames(
                        beanFactory,
                        "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter")
                .isEmpty();
        boolean methodSecurityAnnotations = discoverMethodSecurityAnnotations(beanFactory);

        SecurityContext context = new SecurityContext(
                chains,
                passwordEncoders,
                corsConfigs,
                corsSourcePresent,
                jwtDecoderTypes,
                methodSecurityEnabled,
                globalMethodSecurityLegacy,
                methodSecurityAnnotations,
                webSecurityConfigurerAdapter,
                environment);
        return new SecurityDiscovery(context, errors);
    }

    private static FilterChainModel toChainModel(int index, SecurityFilterChain chain) {
        List<Filter> filters = chain.getFilters();
        List<String> filterNames =
                filters.stream().map(f -> f.getClass().getSimpleName()).toList();
        String matcher = matcherDescription(chain);
        Boolean permitsAllAnonymous = simulateAnonymous(filters);
        Boolean sessionFixationDisabled = detectSessionFixationDisabled(filters);
        List<String> headerWriters = detectHeaderWriters(filters);
        return new FilterChainModel(
                index, matcher, filterNames, permitsAllAnonymous, sessionFixationDisabled, headerWriters);
    }

    private static String matcherDescription(SecurityFilterChain chain) {
        try {
            if (chain instanceof DefaultSecurityFilterChain dfc) {
                return String.valueOf(dfc.getRequestMatcher());
            }
        } catch (RuntimeException | LinkageError ex) {
            // fall through
        }
        return "(custom chain: " + chain.getClass().getSimpleName() + ")";
    }

    private static Boolean simulateAnonymous(List<Filter> filters) {
        AuthorizationManager<HttpServletRequest> manager = authorizationManager(filters);
        if (manager == null) {
            return null;
        }
        try {
            Authentication anonymous = new AnonymousAuthenticationToken(
                    "bootui-advisor", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
            HttpServletRequest request = simulatedRequest();
            AuthorizationResult result = manager.authorize(() -> anonymous, request);
            return result != null && result.isGranted();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static AuthorizationManager<HttpServletRequest> authorizationManager(List<Filter> filters) {
        for (Filter filter : filters) {
            if (filter instanceof AuthorizationFilter authorizationFilter) {
                try {
                    return (AuthorizationManager<HttpServletRequest>) authorizationFilter.getAuthorizationManager();
                } catch (RuntimeException | LinkageError ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Boolean detectSessionFixationDisabled(List<Filter> filters) {
        for (Filter filter : filters) {
            if (!"SessionManagementFilter".equals(filter.getClass().getSimpleName())) {
                continue;
            }
            Object strategy = readField(filter, "sessionAuthenticationStrategy");
            if (strategy == null) {
                return null;
            }
            List<String> strategyNames = new ArrayList<>();
            collectStrategyNames(strategy, strategyNames, 0);
            boolean hasFixationProtection = strategyNames.stream()
                    .anyMatch(name -> name.contains("SessionFixationProtectionStrategy")
                            || name.contains("ChangeSessionIdAuthenticationStrategy"));
            boolean hasNullStrategy =
                    strategyNames.stream().anyMatch(name -> name.contains("NullAuthenticatedSessionStrategy"));
            if (hasFixationProtection) {
                return false;
            }
            if (hasNullStrategy) {
                return true;
            }
            return null;
        }
        return null;
    }

    private static void collectStrategyNames(Object strategy, List<String> names, int depth) {
        if (strategy == null || depth > 4) {
            return;
        }
        names.add(strategy.getClass().getSimpleName());
        Object delegates = readField(strategy, "delegateStrategies");
        if (delegates instanceof Iterable<?> iterable) {
            for (Object delegate : iterable) {
                collectStrategyNames(delegate, names, depth + 1);
            }
        }
    }

    private static List<String> detectHeaderWriters(List<Filter> filters) {
        for (Filter filter : filters) {
            if (!"HeaderWriterFilter".equals(filter.getClass().getSimpleName())) {
                continue;
            }
            Object writers = readField(filter, "headerWriters");
            List<String> names = new ArrayList<>();
            if (writers instanceof Iterable<?> iterable) {
                for (Object writer : iterable) {
                    if (writer != null) {
                        names.add(writer.getClass().getSimpleName());
                    }
                }
            }
            return names;
        }
        return List.of();
    }

    private static boolean discoverCors(
            ListableBeanFactory beanFactory, List<CorsConfigModel> corsConfigs, List<String> errors) {
        if (beanFactory == null) {
            return false;
        }
        Map<String, CorsConfigurationSource> sources;
        try {
            sources = beanFactory.getBeansOfType(CorsConfigurationSource.class);
        } catch (RuntimeException | LinkageError ex) {
            errors.add("CORS sources: " + safeMessage(ex));
            return false;
        }
        if (sources.isEmpty()) {
            return false;
        }
        for (CorsConfigurationSource source : sources.values()) {
            if (source instanceof UrlBasedCorsConfigurationSource urlSource) {
                try {
                    Map<String, CorsConfiguration> configurations = urlSource.getCorsConfigurations();
                    for (Map.Entry<String, CorsConfiguration> entry : configurations.entrySet()) {
                        CorsConfiguration config = entry.getValue();
                        if (config == null) {
                            continue;
                        }
                        corsConfigs.add(new CorsConfigModel(
                                entry.getKey(),
                                config.getAllowedOrigins(),
                                config.getAllowedOriginPatterns(),
                                config.getAllowedMethods(),
                                config.getAllowedHeaders(),
                                config.getAllowCredentials()));
                    }
                } catch (RuntimeException | LinkageError ex) {
                    errors.add("CORS configuration: " + safeMessage(ex));
                }
            }
        }
        return true;
    }

    private static boolean discoverMethodSecurityAnnotations(ListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return false;
        }
        List<Class<?>> annotations = new ArrayList<>();
        for (String name : List.of(
                "org.springframework.security.access.prepost.PreAuthorize",
                "org.springframework.security.access.prepost.PostAuthorize",
                "org.springframework.security.access.annotation.Secured",
                "jakarta.annotation.security.RolesAllowed")) {
            Class<?> type = classForName(name);
            if (type != null) {
                annotations.add(type);
            }
        }
        if (annotations.isEmpty()) {
            return false;
        }
        try {
            String[] beanNames = beanFactory.getBeanDefinitionNames();
            int scanned = 0;
            for (String beanName : beanNames) {
                if (scanned++ > MAX_BEAN_SCAN) {
                    break;
                }
                Class<?> type;
                try {
                    type = beanFactory.getType(beanName);
                } catch (RuntimeException | LinkageError ex) {
                    continue;
                }
                if (type == null) {
                    continue;
                }
                String packageName = type.getPackageName();
                if (packageName.startsWith("org.springframework")
                        || packageName.startsWith("io.github.jdubois.bootui")) {
                    continue;
                }
                if (typeUsesAnnotation(type, annotations)) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean typeUsesAnnotation(Class<?> type, List<Class<?>> annotations) {
        try {
            for (Class<?> annotation : annotations) {
                if (type.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) annotation)) {
                    return true;
                }
            }
            Method[] methods;
            try {
                methods = type.getMethods();
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
            for (Method method : methods) {
                for (Class<?> annotation : annotations) {
                    if (method.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) annotation)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
        return false;
    }

    // ── Reflection / proxy helpers ───────────────────────────────────────────────

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

    private static HttpServletRequest simulatedRequest() {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            return switch (name) {
                case "getMethod" -> "GET";
                case "getServletPath", "getRequestURI", "getPathInfo" -> "/";
                case "getContextPath" -> "";
                case "getScheme" -> "http";
                case "getProtocol" -> "HTTP/1.1";
                case "getServerName", "getRemoteHost", "getLocalName" -> "localhost";
                case "getRemoteAddr", "getLocalAddr" -> "127.0.0.1";
                case "getRequestURL" -> new StringBuffer("http://localhost/");
                default -> defaultValue(method);
            };
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                SecurityScanner.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType.equals(java.util.Enumeration.class)) {
            return Collections.emptyEnumeration();
        }
        if (returnType.equals(boolean.class)) {
            return Boolean.FALSE;
        }
        if (returnType.equals(int.class)) {
            return 0;
        }
        if (returnType.equals(long.class)) {
            return 0L;
        }
        if (returnType.equals(short.class)) {
            return (short) 0;
        }
        if (returnType.equals(byte.class)) {
            return (byte) 0;
        }
        if (returnType.equals(double.class)) {
            return 0d;
        }
        if (returnType.equals(float.class)) {
            return 0f;
        }
        if (returnType.equals(char.class)) {
            return '\0';
        }
        return null;
    }

    private static final String PASSWORD_ENCODER_CLASS = "org.springframework.security.crypto.password.PasswordEncoder";

    private static List<PasswordEncoderModel> discoverPasswordEncoders(ListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return List.of();
        }
        Class<?> type = classForName(PASSWORD_ENCODER_CLASS);
        if (type == null) {
            return List.of();
        }
        Map<String, ?> beans;
        try {
            beans = beanFactory.getBeansOfType(type);
        } catch (RuntimeException | LinkageError ex) {
            return beanTypeNames(beanFactory, PASSWORD_ENCODER_CLASS).stream()
                    .map(name -> new PasswordEncoderModel(name, null))
                    .toList();
        }
        List<PasswordEncoderModel> models = new ArrayList<>();
        for (Object encoder : beans.values()) {
            if (encoder == null) {
                continue;
            }
            models.add(new PasswordEncoderModel(encoder.getClass().getName(), bcryptStrength(encoder)));
        }
        return models;
    }

    private static Integer bcryptStrength(Object encoder) {
        if (!encoder.getClass().getName().contains("BCryptPasswordEncoder")) {
            return null;
        }
        Object value = readField(encoder, "strength");
        return (value instanceof Integer strength) ? strength : null;
    }

    private static List<String> beanTypeNames(ListableBeanFactory beanFactory, String className) {
        if (beanFactory == null) {
            return List.of();
        }
        Class<?> type = classForName(className);
        if (type == null) {
            return List.of();
        }
        try {
            String[] names = beanFactory.getBeanNamesForType(type);
            List<String> result = new ArrayList<>();
            for (String name : names) {
                try {
                    Class<?> beanType = beanFactory.getType(name);
                    result.add(beanType == null ? name : beanType.getName());
                } catch (RuntimeException | LinkageError ex) {
                    result.add(name);
                }
            }
            return result;
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }

    // ── Aggregation ──────────────────────────────────────────────────────────────

    private List<SecuritySeverityCountDto> severityCounts(List<SecurityRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (SecurityRuleResultDto result : results) {
            if (isViolation(result)) {
                counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new SecuritySeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<SecurityRuleResultDto> violationResults(List<SecurityRuleResultDto> results) {
        return results.stream()
                .filter(SecurityScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    private static boolean isViolation(SecurityRuleResultDto result) {
        return SecurityRuleSupport.VIOLATION.equals(result.status());
    }

    private record SecurityDiscovery(SecurityContext context, List<String> errors) {

        SecurityDiscovery {
            errors = List.copyOf(errors);
        }

        static SecurityDiscovery empty(String reason) {
            return new SecurityDiscovery(null, List.of(reason == null ? "Unavailable." : reason));
        }
    }
}
