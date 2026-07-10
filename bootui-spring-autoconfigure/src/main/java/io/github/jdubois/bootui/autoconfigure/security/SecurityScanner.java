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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;
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
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
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
                violations,
                analysisErrors(results));
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
                marked,
                report.analysisErrors());
    }

    static List<SecurityRuleResultDto> analysisErrors(List<SecurityRuleResultDto> results) {
        return results.stream()
                .filter(result -> SecurityRuleSupport.ERROR.equals(result.status()))
                .sorted(Comparator.comparing(SecurityRuleResultDto::id))
                .toList();
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
        List<String> oauth2TokenValidatorTypes =
                beanTypeNames(beanFactory, "org.springframework.security.oauth2.core.OAuth2TokenValidator");
        List<CorsConfigModel> corsConfigs = new ArrayList<>();
        CorsDiscoveryResult corsDiscovery = discoverCors(beanFactory, corsConfigs, errors);
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
        boolean methodSecurityAnnotations = discoverMethodSecurityAnnotations(beanFactory);
        boolean strictHttpFirewallWeakened = discoverStrictHttpFirewallWeakened(beanFactory);
        boolean hideUserNotFoundExceptionsDisabled = discoverHideUserNotFoundExceptionsDisabled(beanFactory);
        List<String> opaqueTokenIntrospectorTypes = beanTypeNames(
                beanFactory,
                "org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector");
        boolean generatedUserDetailsManagerPresent = discoverGeneratedUserDetailsManagerPresent(beanFactory);

        SecurityContext context = new SecurityContext(
                chains,
                passwordEncoders,
                corsConfigs,
                corsDiscovery.sourcePresent(),
                jwtDecoderTypes,
                methodSecurityEnabled,
                globalMethodSecurityLegacy,
                methodSecurityAnnotations,
                corsDiscovery.customSourcePresent(),
                oauth2TokenValidatorTypes,
                strictHttpFirewallWeakened,
                hideUserNotFoundExceptionsDisabled,
                opaqueTokenIntrospectorTypes,
                generatedUserDetailsManagerPresent,
                environment);
        return new SecurityDiscovery(context, errors);
    }

    private static FilterChainModel toChainModel(int index, SecurityFilterChain chain) {
        List<Filter> filters = chain.getFilters();
        List<String> filterNames =
                filters.stream().map(f -> f.getClass().getSimpleName()).toList();
        String matcher = matcherDescription(chain);
        AuthorizationManager<HttpServletRequest> authorizationManager = authorizationManager(filters);
        Boolean permitsAllAnonymous = simulateAnonymous(authorizationManager);
        Boolean sessionFixationDisabled = detectSessionFixationDisabled(filters);
        HeaderWriterInfo headerWriters = detectHeaderWriters(filters);
        Boolean authorizationRuleShadowed = detectAuthorizationRuleShadowed(authorizationManager);
        Integer rememberMeKeyLength = detectRememberMeKeyLength(filters);
        return new FilterChainModel(
                index,
                matcher,
                filterNames,
                permitsAllAnonymous,
                sessionFixationDisabled,
                headerWriters.names(),
                headerWriters.hstsMaxAgeSeconds(),
                headerWriters.hstsIncludeSubdomains(),
                headerWriters.cspPolicyDirectives(),
                headerWriters.cspReportOnly(),
                authorizationRuleShadowed,
                rememberMeKeyLength);
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

    private static Boolean simulateAnonymous(AuthorizationManager<HttpServletRequest> manager) {
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

    private static AuthorizationManager<HttpServletRequest> authorizationManager(List<Filter> filters) {
        for (Filter filter : filters) {
            if (filter instanceof AuthorizationFilter authorizationFilter) {
                try {
                    return authorizationFilter.getAuthorizationManager();
                } catch (RuntimeException | LinkageError ex) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Matches a Spring Security 7 {@code PathPatternRequestMatcher} toString of the bare, unscoped
     * catch-all form {@code "PathPattern [/**]"} -- deliberately excluding a method-qualified variant
     * such as {@code "PathPattern [GET /**]"}, which only shadows requests using that one HTTP method
     * and so is not treated as an unconditional catch-all here.
     */
    private static final Pattern UNCONDITIONAL_CATCH_ALL_PATTERN = Pattern.compile("\\[/\\*\\*]");

    /**
     * {@code true} when {@code null} (indeterminate -- the chain's {@code AuthorizationManager} could
     * not be introspected), {@code true} when an earlier, broader {@code authorizeHttpRequests}
     * matcher shadows a later, narrower one (so the later rule can never take effect since Spring
     * Security's {@code RequestMatcherDelegatingAuthorizationManager} evaluates matchers in
     * declaration order and returns on the first match), {@code false} when no such shadowing was
     * detected. Only an unconditional, method-agnostic catch-all matcher (Spring Security's
     * {@code AnyRequestMatcher}, or an explicit {@code "/**"} pattern with no HTTP-method
     * restriction) is treated as shadowing -- a method-scoped catch-all such as
     * {@code requestMatchers(HttpMethod.GET, "/**")} is deliberately not flagged, since it only
     * shadows requests using that one method and determining general matcher subsumption across
     * methods and path patterns is out of scope for this bounded, low-false-positive check.
     */
    private static Boolean detectAuthorizationRuleShadowed(AuthorizationManager<HttpServletRequest> manager) {
        if (!(manager instanceof RequestMatcherDelegatingAuthorizationManager)) {
            return null;
        }
        Object mappingsField = readField(manager, "mappings");
        if (!(mappingsField instanceof List<?> mappings) || mappings.size() < 2) {
            return false;
        }
        // The last entry is allowed to be a catch-all (it is the final fallback rule and shadows
        // nothing after it), so only entries before it are checked.
        for (int i = 0; i < mappings.size() - 1; i++) {
            if (mappings.get(i) instanceof RequestMatcherEntry<?> matcherEntry
                    && isUnconditionalCatchAllMatcher(matcherEntry.getRequestMatcher())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnconditionalCatchAllMatcher(RequestMatcher matcher) {
        if (matcher instanceof AnyRequestMatcher) {
            return true;
        }
        String normalized = String.valueOf(matcher).toLowerCase(Locale.ROOT).trim();
        return normalized.equals("any request")
                || normalized.contains("anyrequest")
                || UNCONDITIONAL_CATCH_ALL_PATTERN.matcher(normalized).find();
    }

    /**
     * The length of the signing key configured on this chain's {@code RememberMeAuthenticationFilter}
     * (via its {@code AbstractRememberMeServices}), or {@code null} when no remember-me filter is
     * present or its key could not be read. Only the length is retained -- never the key itself --
     * so a short/predictable key can be flagged without the key value ever leaving this process.
     */
    private static Integer detectRememberMeKeyLength(List<Filter> filters) {
        for (Filter filter : filters) {
            if (filter instanceof RememberMeAuthenticationFilter rememberMeFilter) {
                try {
                    RememberMeServices services = rememberMeFilter.getRememberMeServices();
                    if (services instanceof AbstractRememberMeServices abstractServices) {
                        String key = abstractServices.getKey();
                        return key == null ? null : key.length();
                    }
                } catch (RuntimeException | LinkageError ex) {
                    return null;
                }
                return null;
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

    /**
     * Simple class names of the installed {@code HeaderWriter}s, plus the HSTS max-age/
     * includeSubDomains and CSP policyDirectives fields when those specific writers are present (read
     * via the same reflection helper used for {@link #bcryptStrength(Object)}).
     */
    private record HeaderWriterInfo(
            List<String> names,
            Long hstsMaxAgeSeconds,
            Boolean hstsIncludeSubdomains,
            String cspPolicyDirectives,
            Boolean cspReportOnly) {}

    private static final HeaderWriterInfo NO_HEADER_WRITERS = new HeaderWriterInfo(List.of(), null, null, null, null);

    private static HeaderWriterInfo detectHeaderWriters(List<Filter> filters) {
        for (Filter filter : filters) {
            if (!"HeaderWriterFilter".equals(filter.getClass().getSimpleName())) {
                continue;
            }
            Object writers = readField(filter, "headerWriters");
            List<String> names = new ArrayList<>();
            Long hstsMaxAgeSeconds = null;
            Boolean hstsIncludeSubdomains = null;
            String cspPolicyDirectives = null;
            Boolean cspReportOnly = null;
            if (writers instanceof Iterable<?> iterable) {
                for (Object writer : iterable) {
                    if (writer == null) {
                        continue;
                    }
                    String simpleName = writer.getClass().getSimpleName();
                    names.add(simpleName);
                    if (simpleName.contains("Hsts")) {
                        if (readField(writer, "maxAgeInSeconds") instanceof Long maxAge) {
                            hstsMaxAgeSeconds = maxAge;
                        }
                        if (readField(writer, "includeSubDomains") instanceof Boolean includeSubDomains) {
                            hstsIncludeSubdomains = includeSubDomains;
                        }
                    } else if (simpleName.contains("ContentSecurityPolicy")
                            && readField(writer, "policyDirectives") instanceof String directives) {
                        cspPolicyDirectives = directives;
                        if (readField(writer, "reportOnly") instanceof Boolean reportOnly) {
                            cspReportOnly = reportOnly;
                        }
                    }
                }
            }
            return new HeaderWriterInfo(
                    names, hstsMaxAgeSeconds, hstsIncludeSubdomains, cspPolicyDirectives, cspReportOnly);
        }
        return NO_HEADER_WRITERS;
    }

    /**
     * Whether at least one {@code CorsConfigurationSource} bean was found ({@code sourcePresent}),
     * and whether at least one of those beans is a type other than {@code
     * UrlBasedCorsConfigurationSource} ({@code customSourcePresent}) -- the only source type this
     * scanner can actually introspect the per-path {@code CorsConfiguration} entries of. A custom
     * source means the CORS-related rules cannot see the real configuration and should render
     * indeterminate rather than silently passing.
     */
    private record CorsDiscoveryResult(boolean sourcePresent, boolean customSourcePresent) {}

    private static final CorsDiscoveryResult NO_CORS_SOURCES = new CorsDiscoveryResult(false, false);

    private static CorsDiscoveryResult discoverCors(
            ListableBeanFactory beanFactory, List<CorsConfigModel> corsConfigs, List<String> errors) {
        if (beanFactory == null) {
            return NO_CORS_SOURCES;
        }
        Map<String, CorsConfigurationSource> sources;
        try {
            sources = beanFactory.getBeansOfType(CorsConfigurationSource.class);
        } catch (RuntimeException | LinkageError ex) {
            errors.add("CORS sources: " + safeMessage(ex));
            return NO_CORS_SOURCES;
        }
        if (sources.isEmpty()) {
            return NO_CORS_SOURCES;
        }
        boolean customSourcePresent = false;
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
            } else {
                customSourcePresent = true;
            }
        }
        return new CorsDiscoveryResult(true, customSourcePresent);
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

    /**
     * Default tokens a {@code StrictHttpFirewall} blocks in its {@code encodedUrlBlocklist} unless a
     * setter such as {@code setAllowUrlEncodedSlash(true)} explicitly relaxes it. Used to detect when
     * a custom {@code StrictHttpFirewall} bean has weakened Spring Security's default URL validation
     * (e.g. re-enabling the encoded-slash / backslash / semicolon path-confusion vectors historically
     * exploited to bypass authorization rules).
     */
    private static final List<String> FIREWALL_DEFAULT_BLOCKED_TOKENS = List.of("%2f", "%5c", ";", "%2f%2f");

    private static boolean discoverStrictHttpFirewallWeakened(ListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return false;
        }
        Map<String, StrictHttpFirewall> firewalls;
        try {
            firewalls = beanFactory.getBeansOfType(StrictHttpFirewall.class);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
        for (StrictHttpFirewall firewall : firewalls.values()) {
            if (firewall == null) {
                continue;
            }
            Object blocklist = readField(firewall, "encodedUrlBlocklist");
            if (blocklist instanceof Set<?> blocked
                    && FIREWALL_DEFAULT_BLOCKED_TOKENS.stream().anyMatch(token -> !blocked.contains(token))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code true} when a {@code DaoAuthenticationProvider} (or another {@code
     * AbstractUserDetailsAuthenticationProvider}) bean has been explicitly configured with {@code
     * hideUserNotFoundExceptions=false}, which lets an attacker distinguish "user not found" from
     * "bad password" and enumerate valid usernames. The field defaults to {@code true}, so this only
     * fires when a host application has actively disabled the protection.
     */
    private static boolean discoverHideUserNotFoundExceptionsDisabled(ListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return false;
        }
        Map<String, AbstractUserDetailsAuthenticationProvider> providers;
        try {
            providers = beanFactory.getBeansOfType(AbstractUserDetailsAuthenticationProvider.class);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
        for (AbstractUserDetailsAuthenticationProvider provider : providers.values()) {
            if (provider != null && Boolean.FALSE.equals(readField(provider, "hideUserNotFoundExceptions"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code true} when Spring Boot's own auto-configured {@code InMemoryUserDetailsManager} bean is
     * present -- the single generated-password "user" account {@code
     * UserDetailsServiceAutoConfiguration} creates only when no other {@code UserDetailsService},
     * {@code AuthenticationManager}, or {@code AuthenticationProvider} bean exists. Matched by the
     * exact bean name Spring Boot's auto-configuration registers it under ({@code
     * inMemoryUserDetailsManager}), since the bean's type alone would also match a host application's
     * own, deliberately-configured {@code InMemoryUserDetailsManager}.
     */
    private static boolean discoverGeneratedUserDetailsManagerPresent(ListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return false;
        }
        try {
            return beanFactory.containsBean("inMemoryUserDetailsManager");
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
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
