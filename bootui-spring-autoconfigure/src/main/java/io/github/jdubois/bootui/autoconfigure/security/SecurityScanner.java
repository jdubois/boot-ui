package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.AuthorizationMapping;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.ChainDetails;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.FilterChainModel;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.MatcherFacts;
import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.PasswordEncoderModel;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.core.dto.SecurityScanStatusDto;
import io.github.jdubois.bootui.core.dto.SecuritySeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.SingleResultAuthorizationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.debug.DebugFilter;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.session.SessionManagementFilter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Bounded, on-demand Spring Security advisor.
 *
 * <p>The scanner reads the registered {@code SecurityFilterChain} beans and related security beans,
 * builds a read-only model, and runs a curated registry of static best-practice checks. It never
 * executes application authorization, matcher, credential or endpoint callbacks, and never surfaces
 * credentials, keys, or session identifiers. Unsupported structures remain unknown.</p>
 */
final class SecurityScanner {

    private static final String ANALYZER = "BootUI Spring Security Advisor";
    private static final String DISCLAIMER =
            "Heuristic Spring Security rules run against the host application's registered filter chains "
                    + "and security beans only. These checks are review prompts, not verdicts, and should be "
                    + "validated against the application's threat model.";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final int MAX_BEAN_SCAN = 5000;
    private static final String SPRING_SECURITY_FILTER_CHAIN_BEAN_NAME = "springSecurityFilterChain";
    private static final String OBSERVATION_AUTHORIZATION_MANAGER_CLASS_NAME =
            "org.springframework.security.authorization.ObservationAuthorizationManager";

    private static final Comparator<SecurityRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (SecurityRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(Comparator.comparingInt(SecurityRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(SecurityRuleResultDto::id);

    private final Supplier<SecurityDiscovery> discoverySupplier;
    private final Environment environment;
    private final Clock clock;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

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
        return singleFlight.run(ActionOperations.SECURITY_SCAN, this::doScan);
    }

    private SecurityReport doScan() {
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
        boolean incomplete = results.stream()
                .anyMatch(result -> SecurityRuleSupport.ERROR.equals(result.status())
                        || SecurityRuleSupport.SKIPPED.equals(result.status()));
        String status = discovery.errors().isEmpty() && !incomplete ? "SCANNED" : "PARTIAL";
        String message = "Security Advisor completed against " + chains + " filter chain" + (chains == 1 ? "." : "s.");
        if (!discovery.errors().isEmpty()) {
            message += " Some configuration could not be read: " + String.join("; ", discovery.errors());
        }
        if (incomplete) {
            message += " Unsupported or incomplete evidence remains unknown; skipped checks are not security passes.";
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
        lastContext = null;
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
        environment = SecurityEnvironmentSnapshot.capture(environment);
        ListableBeanFactory beanFactory;
        try {
            beanFactory = beanFactories.getIfAvailable();
        } catch (RuntimeException | LinkageError ex) {
            return SecurityDiscovery.empty(safeMessage(ex));
        }
        FilterChainProxy proxy = null;
        boolean securityDebugFilterPresent = false;
        if (beanFactory instanceof SingletonBeanRegistry singletons) {
            Object securityFilter = singletons.getSingleton(SPRING_SECURITY_FILTER_CHAIN_BEAN_NAME);
            if (securityFilter instanceof FilterChainProxy filterChainProxy) {
                proxy = nativeFilterChainProxy(filterChainProxy);
            } else if (securityFilter instanceof DebugFilter debugFilter) {
                Object wrapped = readField(debugFilter, "filterChainProxy");
                if (wrapped instanceof FilterChainProxy filterChainProxy)
                    proxy = nativeFilterChainProxy(filterChainProxy);
                securityDebugFilterPresent = true;
            }
            if (proxy == null) {
                for (Object singleton : existingSingletons(beanFactory, FilterChainProxy.class)) {
                    proxy = nativeFilterChainProxy((FilterChainProxy) singleton);
                    if (proxy != null) break;
                }
            }
        }
        if (proxy == null) {
            return SecurityDiscovery.empty("No Spring Security FilterChainProxy is available.");
        }

        List<String> errors = new ArrayList<>();
        if (beanFactory instanceof SingletonBeanRegistry registry
                && registry.getSingletonNames().length > MAX_BEAN_SCAN) {
            errors.add("Singleton inventory limit reached.");
        }
        List<FilterChainModel> chains = new ArrayList<>();
        List<Filter> activeFilters = new ArrayList<>();
        Map<Integer, List<Filter>> chainFilters = new java.util.LinkedHashMap<>();
        try {
            Object rawChains = readField(proxy, "filterChains");
            if (!(rawChains instanceof List<?> securityChains)) {
                throw new IllegalStateException();
            }
            if (securityChains.size() > MAX_BEAN_SCAN) errors.add("Chain inventory limit reached.");
            for (int i = 0; i < Math.min(securityChains.size(), MAX_BEAN_SCAN); i++) {
                try {
                    Object candidate = securityChains.get(i);
                    if (isBootUiChain(beanFactory, candidate)) continue;
                    if (!(candidate instanceof DefaultSecurityFilterChain)
                            || candidate.getClass() != DefaultSecurityFilterChain.class) {
                        chains.add(unknownChain(i));
                        errors.add("Chain " + i + ": unsupported chain implementation.");
                        continue;
                    }
                    SecurityFilterChain chain = (SecurityFilterChain) candidate;
                    List<Filter> filters = safeFilters(chain);
                    activeFilters.addAll(filters);
                    chainFilters.put(i, filters);
                    FilterChainModel model = toChainModel(i, chain);
                    chains.add(model);
                    if (!model.details().filtersKnown()
                            || !model.details().headersKnown()
                            || model.details().matcher() == null
                            || !model.details().matcher().complete()
                            || model.hasAuthorizationFilter() && model.permitsAllAnonymous() == null) {
                        errors.add("Chain " + i + ": some framework metadata is unsupported.");
                    }
                } catch (RuntimeException | LinkageError ex) {
                    chains.add(unknownChain(i));
                    errors.add("Chain " + i + ": " + safeMessage(ex));
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            errors.add("Filter chains: " + safeMessage(ex));
        }

        List<Object> providers = activeProviders(activeFilters);
        if (providers.size() >= 512) errors.add("Authentication provider inventory limit reached.");
        List<PasswordEncoderModel> passwordEncoders = discoverPasswordEncoders(providers);
        List<String> jwtDecoderTypes = providers.stream()
                .filter(
                        provider -> provider.getClass()
                                .getName()
                                .equals(
                                        "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider"))
                .map(provider -> readField(provider, "jwtDecoder"))
                .filter(java.util.Objects::nonNull)
                .map(decoder -> decoder.getClass().getName())
                .toList();
        List<String> oauth2TokenValidatorTypes =
                beanTypeNames(beanFactory, "org.springframework.security.oauth2.core.OAuth2TokenValidator");
        List<CorsConfigModel> corsConfigs = new ArrayList<>();
        CorsDiscoveryResult corsDiscovery = discoverAttachedCors(chains, chainFilters, corsConfigs, errors);
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
        boolean methodSecurityAnnotations = false;
        boolean strictHttpFirewallWeakened = firewallWeakened(readField(proxy, "firewall"));
        boolean hideUserNotFoundExceptionsDisabled = providers.stream()
                .anyMatch(provider -> provider.getClass()
                                .getName()
                                .equals("org.springframework.security.authentication.dao.DaoAuthenticationProvider")
                        && Boolean.FALSE.equals(readField(provider, "hideUserNotFoundExceptions")));
        List<String> opaqueTokenIntrospectorTypes = providers.stream()
                .filter(
                        provider -> provider.getClass()
                                .getName()
                                .equals(
                                        "org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider"))
                .map(provider -> readField(provider, "introspector"))
                .filter(java.util.Objects::nonNull)
                .map(introspector -> introspector.getClass().getName())
                .toList();
        boolean generatedUserDetailsManagerPresent = discoverGeneratedUserDetailsManagerPresent(beanFactory, providers);

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
                securityDebugFilterPresent,
                environment,
                discoverEvidence(beanFactory, providers, environment, errors));
        return new SecurityDiscovery(context, errors);
    }

    private static FilterChainModel toChainModel(int index, SecurityFilterChain chain) {
        List<Filter> filters = safeFilters(chain);
        List<String> filterNames =
                filters.stream().map(SecurityScanner::frameworkTypeName).toList();
        String matcher = matcherDescription(chain);
        MatcherFacts matcherFacts = matcherFacts(readField(chain, "requestMatcher"), 0);
        AuthorizationManager<HttpServletRequest> authorizationManager = authorizationManager(filters);
        List<AuthorizationMapping> mappings = authorizationMappings(authorizationManager);
        Boolean permitsAllAnonymous = blanketGrant(mappings);
        Boolean sessionFixationDisabled = detectSessionFixationDisabled(filters);
        HeaderWriterInfo headerWriters = detectHeaderWriters(filters);
        Boolean authorizationRuleShadowed = detectAuthorizationRuleShadowed(authorizationManager);
        Integer rememberMeKeyLength = detectRememberMeKeyLength(filters);
        Boolean statelessSecurityContext = detectStatelessSecurityContext(filters);
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
                rememberMeKeyLength,
                statelessSecurityContext,
                null,
                null,
                new ChainDetails(
                        filterMetadataKnown(filters),
                        headerWriters.known(),
                        matcherFacts.unconditional(),
                        matcherFacts,
                        mappings,
                        bearerSavesSession(filters),
                        unconditionalHttpsRedirect(filters)));
    }

    private static String matcherDescription(SecurityFilterChain chain) {
        return matcherDescription(matcherFacts(readField(chain, "requestMatcher"), 0));
    }

    private static String matcherDescription(MatcherFacts facts) {
        String description = facts.unconditional()
                ? "any request"
                : "path".equals(facts.kind())
                        ? (facts.method() == null ? "" : facts.method() + " ") + facts.path()
                        : facts.children().isEmpty()
                                ? "(" + facts.kind() + " matcher)"
                                : "(" + facts.kind() + ": "
                                        + String.join(
                                                ", ",
                                                facts.children().stream()
                                                        .limit(8)
                                                        .map(SecurityScanner::matcherDescription)
                                                        .toList())
                                        + (facts.children().size() > 8 ? ", ..." : "") + ")";
        return description.length() > 512 ? description.substring(0, 509) + "..." : description;
    }

    private static AuthorizationManager<HttpServletRequest> authorizationManager(List<Filter> filters) {
        for (Filter filter : filters) {
            if (filter.getClass() == AuthorizationFilter.class) {
                Object manager = readField(filter, "authorizationManager");
                if (manager instanceof AuthorizationManager<?> authorizationManager) {
                    @SuppressWarnings("unchecked")
                    AuthorizationManager<HttpServletRequest> typed =
                            (AuthorizationManager<HttpServletRequest>) authorizationManager;
                    return typed;
                }
            }
        }
        return null;
    }

    /**
     * {@code null} when the chain's {@code AuthorizationManager} could not be introspected, {@code
     * true} when an earlier, broader {@code authorizeHttpRequests} matcher shadows a later, narrower
     * one, or {@code false} when no such shadowing was detected. Only an unconditional,
     * method-agnostic catch-all matcher is treated as shadowing.
     */
    private static Boolean detectAuthorizationRuleShadowed(AuthorizationManager<HttpServletRequest> manager) {
        AuthorizationManager<HttpServletRequest> unwrappedManager = unwrapObservationAuthorizationManager(manager);
        if (!(unwrappedManager instanceof RequestMatcherDelegatingAuthorizationManager)) {
            return null;
        }
        Object mappingsField = readField(unwrappedManager, "mappings");
        if (!(mappingsField instanceof List<?> mappings)) {
            return null;
        }
        if (mappings.size() < 2) {
            return false;
        }
        // The last entry is allowed to be a catch-all (it is the final fallback rule and shadows
        // nothing after it), so only entries before it are checked.
        for (int i = 0; i < mappings.size() - 1; i++) {
            if (!(mappings.get(i) instanceof RequestMatcherEntry<?> matcherEntry)) {
                return null;
            }
            if (isUnconditionalCatchAllMatcher(matcherEntry.getRequestMatcher())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnconditionalCatchAllMatcher(RequestMatcher matcher) {
        return matcherFacts(matcher, 0).unconditional();
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
                    Object services = readField(rememberMeFilter, "rememberMeServices");
                    if (services != null
                            && services.getClass()
                                    .getName()
                                    .equals(
                                            "org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices")) {
                        Object key = readField(services, "key");
                        return key instanceof String text ? text.length() : null;
                    }
                } catch (RuntimeException | LinkageError ex) {
                    return null;
                }
                return null;
            }
        }
        return null;
    }

    /**
     * {@code TRUE} when the chain never persists its {@code SecurityContext} in an HTTP session --
     * what {@code sessionManagement().sessionCreationPolicy(STATELESS)} configures -- {@code FALSE}
     * when an {@code HttpSessionSecurityContextRepository} takes part in it, and {@code null} when the
     * repository is custom, absent, or could not be introspected.
     *
     * <p>The repository is the only reliable statelessness signal on a built chain: {@code
     * SessionManagementFilter} is installed for every {@code sessionManagement} block, stateless
     * included, so its presence says nothing about the policy. Under {@code STATELESS} Spring
     * Security swaps the shared repository for a {@code RequestAttributeSecurityContextRepository}
     * (and gives {@code SessionManagementFilter} a {@code NullSecurityContextRepository}), whereas
     * every session-backed chain keeps an {@code HttpSessionSecurityContextRepository}. Only
     * repository types are inspected -- no session identifier or security context is ever read.</p>
     */
    private static Boolean detectStatelessSecurityContext(List<Filter> filters) {
        for (Filter filter : filters) {
            if (List.of("UsernamePasswordAuthenticationFilter", "OAuth2LoginAuthenticationFilter")
                    .contains(frameworkTypeName(filter))) {
                Object repository = readField(filter, "securityContextRepository");
                return repository instanceof SecurityContextRepository typed ? statelessVerdict(typed, 0) : null;
            }
        }
        return statelessVerdict(securityContextRepository(filters), 0);
    }

    /**
     * The chain's {@code SecurityContextRepository}, preferring the one held by {@code
     * SecurityContextHolderFilter} (the chain-wide repository) and falling back to {@code
     * SessionManagementFilter}'s own, which mirrors the same policy.
     */
    private static SecurityContextRepository securityContextRepository(List<Filter> filters) {
        SecurityContextRepository fallback = null;
        for (Filter filter : filters) {
            if (filter instanceof SecurityContextHolderFilter) {
                if (readField(filter, "securityContextRepository") instanceof SecurityContextRepository repository) {
                    return repository;
                }
            } else if (filter instanceof SessionManagementFilter && fallback == null) {
                if (readField(filter, "securityContextRepository") instanceof SecurityContextRepository repository) {
                    fallback = repository;
                }
            }
        }
        return fallback;
    }

    private static Boolean statelessVerdict(SecurityContextRepository repository, int depth) {
        if (repository == null || depth > 4) {
            return null;
        }
        if (repository.getClass() == HttpSessionSecurityContextRepository.class) {
            return Boolean.FALSE;
        }
        if (repository.getClass() == RequestAttributeSecurityContextRepository.class
                || repository.getClass() == NullSecurityContextRepository.class) {
            return Boolean.TRUE;
        }
        if (repository.getClass() != DelegatingSecurityContextRepository.class) {
            return null;
        }
        if (!(readField(repository, "delegates") instanceof Iterable<?> delegates)) {
            return null;
        }
        boolean anyDelegate = false;
        boolean anyUnknown = false;
        int count = 0;
        for (Object delegate : delegates) {
            if (++count > 128) return null;
            anyDelegate = true;
            Boolean verdict =
                    delegate instanceof SecurityContextRepository nested ? statelessVerdict(nested, depth + 1) : null;
            if (Boolean.FALSE.equals(verdict)) {
                return Boolean.FALSE;
            }
            anyUnknown |= verdict == null;
        }
        return anyDelegate && !anyUnknown ? Boolean.TRUE : null;
    }

    private static Boolean detectSessionFixationDisabled(List<Filter> filters) {
        boolean protectionObserved = false;
        boolean unknown = false;
        for (Filter filter : filters) {
            String name = frameworkTypeName(filter);
            if (!List.of(
                            "SessionManagementFilter",
                            "UsernamePasswordAuthenticationFilter",
                            "OAuth2LoginAuthenticationFilter")
                    .contains(name)) {
                continue;
            }
            Object strategy = readField(
                    filter,
                    "SessionManagementFilter".equals(name) ? "sessionAuthenticationStrategy" : "sessionStrategy");
            if (strategy == null) {
                unknown = true;
                continue;
            }
            List<String> strategyNames = new ArrayList<>();
            collectStrategyNames(strategy, strategyNames, 0);
            if (strategyNames.contains("Unknown")) {
                unknown = true;
                continue;
            }
            boolean hasFixationProtection = strategyNames.stream()
                    .anyMatch(strategyName -> strategyName.contains("SessionFixationProtectionStrategy")
                            || strategyName.contains("ChangeSessionIdAuthenticationStrategy"));
            boolean hasNullStrategy = strategyNames.stream()
                    .anyMatch(strategyName -> strategyName.contains("NullAuthenticatedSessionStrategy"));
            if (hasFixationProtection) {
                protectionObserved = true;
                continue;
            }
            if (hasNullStrategy) {
                return true;
            }
            unknown = true;
        }
        return protectionObserved && !unknown ? false : null;
    }

    private static void collectStrategyNames(Object strategy, List<String> names, int depth) {
        if (strategy == null || depth > 4) {
            names.add("Unknown");
            return;
        }
        names.add(frameworkTypeName(strategy));
        Object delegates = readField(strategy, "delegateStrategies");
        if (delegates instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object delegate : iterable) {
                if (++count > 128) {
                    names.add("Unknown");
                    return;
                }
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
            Boolean cspReportOnly,
            boolean known) {}

    private static final HeaderWriterInfo NO_HEADER_WRITERS =
            new HeaderWriterInfo(List.of(), null, null, null, null, true);

    private static HeaderWriterInfo detectHeaderWriters(List<Filter> filters) {
        for (Filter filter : filters) {
            if (!"HeaderWriterFilter".equals(frameworkTypeName(filter))) {
                continue;
            }
            Object writers = readField(filter, "headerWriters");
            List<String> names = new ArrayList<>();
            Long hstsMaxAgeSeconds = null;
            Boolean hstsIncludeSubdomains = null;
            String cspPolicyDirectives = null;
            Boolean cspReportOnly = null;
            boolean known = writers instanceof List<?>;
            int policies = 0;
            int hstsWriters = 0;
            if (writers instanceof Iterable<?> iterable) {
                int count = 0;
                for (Object writer : iterable) {
                    if (++count > 128) {
                        known = false;
                        break;
                    }
                    if (writer == null) {
                        continue;
                    }
                    String simpleName = frameworkTypeName(writer);
                    if (simpleName.equals("Unknown")
                            || simpleName.contains("Delegating")
                            || simpleName.contains("Static")
                            || simpleName.contains("Composite")) known = false;
                    names.add(simpleName);
                    if (simpleName.contains("Hsts")) {
                        hstsWriters++;
                        Object condition = readField(writer, "requestMatcher");
                        if (condition == null
                                || !condition
                                        .getClass()
                                        .getName()
                                        .equals(
                                                "org.springframework.security.web.header.writers.HstsHeaderWriter$SecureRequestMatcher")) {
                            known = false;
                            continue;
                        }
                        if (readField(writer, "maxAgeInSeconds") instanceof Long maxAge) {
                            hstsMaxAgeSeconds = maxAge;
                        }
                        if (readField(writer, "includeSubDomains") instanceof Boolean includeSubDomains) {
                            hstsIncludeSubdomains = includeSubDomains;
                        }
                    } else if (simpleName.contains("ContentSecurityPolicy")) {
                        if (!(readField(writer, "policyDirectives") instanceof String directives)) {
                            known = false;
                            continue;
                        }
                        if (readField(writer, "reportOnly") instanceof Boolean reportOnly) {
                            if (reportOnly && Boolean.FALSE.equals(cspReportOnly)) continue;
                            if (!reportOnly && Boolean.FALSE.equals(cspReportOnly)) policies++;
                            cspPolicyDirectives = directives;
                            cspReportOnly = reportOnly;
                            if (!reportOnly
                                    && !io.github.jdubois.bootui.engine.security.CspPolicy.analyze(directives)
                                            .complete()) known = false;
                        } else known = false;
                    }
                }
            }
            if (policies > 0) {
                cspPolicyDirectives = null;
                known = false;
            }
            if (hstsWriters > 1) {
                hstsMaxAgeSeconds = null;
                known = false;
            }
            return new HeaderWriterInfo(
                    names, hstsMaxAgeSeconds, hstsIncludeSubdomains, cspPolicyDirectives, cspReportOnly, known);
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

    /**
     * Default tokens a {@code StrictHttpFirewall} blocks in its {@code encodedUrlBlocklist} unless a
     * setter such as {@code setAllowUrlEncodedSlash(true)} explicitly relaxes it. Used to detect when
     * a custom {@code StrictHttpFirewall} bean has weakened Spring Security's default URL validation
     * (e.g. re-enabling the encoded-slash / backslash / semicolon path-confusion vectors historically
     * exploited to bypass authorization rules).
     */
    private static final List<String> FIREWALL_DEFAULT_BLOCKED_TOKENS = List.of("%2f", "%5c", ";", "%2f%2f");

    /**
     * {@code true} when Spring Boot's own auto-configured {@code InMemoryUserDetailsManager} bean is
     * present -- the single generated-password "user" account {@code
     * UserDetailsServiceAutoConfiguration} creates only when no other {@code UserDetailsService},
     * {@code AuthenticationManager}, or {@code AuthenticationProvider} bean exists. Matched by the
     * native factory identity and method, with the resulting service attached to an active provider.
     */
    private static boolean discoverGeneratedUserDetailsManagerPresent(
            ListableBeanFactory beanFactory, List<Object> providers) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory configurable)) {
            return false;
        }
        try {
            if (!configurable.containsSingleton("inMemoryUserDetailsManager")
                    || !configurable.containsBeanDefinition("inMemoryUserDetailsManager")) return false;
            var definition = configurable.getBeanDefinition("inMemoryUserDetailsManager");
            String factory = definition.getFactoryBeanName();
            Object userService = configurable.getSingleton("inMemoryUserDetailsManager");
            Object factoryBean = factory == null ? null : configurable.getSingleton(factory);
            return factoryBean != null
                    && factoryBean
                            .getClass()
                            .getName()
                            .equals(
                                    "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
                    && "inMemoryUserDetailsManager".equals(definition.getFactoryMethodName())
                    && providers.stream()
                            .anyMatch(provider -> readField(provider, "userDetailsService") == userService);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ── Reflection / proxy helpers ───────────────────────────────────────────────

    private static Object readField(Object target, String fieldName) {
        if (target == null) return null;
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

    @SuppressWarnings("unchecked")
    private static AuthorizationManager<HttpServletRequest> unwrapObservationAuthorizationManager(
            AuthorizationManager<HttpServletRequest> manager) {
        AuthorizationManager<?> current = manager;
        for (int depth = 0;
                depth < 8
                        && current != null
                        && OBSERVATION_AUTHORIZATION_MANAGER_CLASS_NAME.equals(
                                current.getClass().getName());
                depth++) {
            Object delegate = readField(current, "delegate");
            if (!(delegate instanceof AuthorizationManager<?> authorizationManager) || delegate == current) {
                return null;
            }
            current = authorizationManager;
        }
        if (current == null
                || OBSERVATION_AUTHORIZATION_MANAGER_CLASS_NAME.equals(
                        current.getClass().getName())) {
            return null;
        }
        return (AuthorizationManager<HttpServletRequest>) current;
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
            List<String> result = new ArrayList<>();
            for (Object singleton : existingSingletons(beanFactory, type)) {
                result.add(singleton.getClass().getName());
            }
            return result;
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name, false, SecurityScanner.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    private static String safeMessage(Throwable ex) {
        return "Framework metadata could not be read (" + (ex instanceof LinkageError ? "linkage" : "runtime") + ").";
    }

    private static FilterChainProxy nativeFilterChainProxy(FilterChainProxy candidate) {
        // Native composites keep their real inventory in a delegate, not the inherited empty list.
        for (int depth = 0; candidate != null && depth < 8; depth++) {
            if (candidate.getClass() == FilterChainProxy.class) return candidate;
            String name = candidate.getClass().getName();
            if (!name.equals(
                            "org.springframework.security.config.annotation.web.configuration.WebSecurityConfiguration$CompositeFilterChainProxy")
                    && !name.equals(
                            "org.springframework.security.config.annotation.web.configuration.WebMvcSecurityConfiguration$CompositeFilterChainProxy"))
                return null;
            Object delegate = readField(candidate, "springSecurityFilterChain");
            if (!(delegate instanceof FilterChainProxy nested) || nested == candidate) return null;
            candidate = nested;
        }
        return null;
    }

    private static List<Filter> safeFilters(SecurityFilterChain chain) {
        if (!(readField(chain, "filters") instanceof List<?> filters) || filters.size() > 512) {
            throw new IllegalStateException();
        }

        List<Filter> result = new ArrayList<>();
        for (Object value : filters) {
            if (!(value instanceof Filter filter)) throw new IllegalStateException();
            result.add(filter);
        }
        return result;
    }

    private static boolean isBootUiChain(ListableBeanFactory beanFactory, Object chain) {
        String name = "bootUiSecurityFilterChain";
        if (!(beanFactory instanceof ConfigurableListableBeanFactory configurable)
                || !configurable.containsBeanDefinition(name)
                || configurable.getSingleton(name) != chain) return false;
        var definition = configurable.getBeanDefinition(name);
        return "io.github.jdubois.bootui.autoconfigure.BootUiSpringSecurityAutoConfiguration"
                        .equals(definition.getFactoryBeanName())
                && name.equals(definition.getFactoryMethodName());
    }

    private static FilterChainModel unknownChain(int index) {
        return new FilterChainModel(
                index,
                "(unsupported chain)",
                List.of(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ChainDetails(false, false, false, unknownMatcher(), List.of(), null, false));
    }

    private static String frameworkTypeName(Object value) {
        if (value == null) return "Unknown";
        String name = value.getClass().getName();
        return FRAMEWORK_TYPES.contains(name) || value.getClass() == CorsFilter.class
                ? value.getClass().getSimpleName()
                : "Unknown";
    }

    private static final Set<String> FRAMEWORK_TYPES = Set.of(
            "org.springframework.security.web.access.intercept.AuthorizationFilter",
            "org.springframework.security.web.access.intercept.FilterSecurityInterceptor",
            "org.springframework.security.web.context.SecurityContextHolderFilter",
            "org.springframework.security.web.context.SecurityContextPersistenceFilter",
            "org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter",
            "org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter",
            "org.springframework.security.web.authentication.www.BasicAuthenticationFilter",
            "org.springframework.security.web.authentication.AnonymousAuthenticationFilter",
            "org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter",
            "org.springframework.security.web.authentication.ui.DefaultLoginPageGeneratingFilter",
            "org.springframework.security.web.authentication.ui.DefaultLogoutPageGeneratingFilter",
            "org.springframework.security.web.authentication.ui.DefaultResourcesFilter",
            "org.springframework.security.web.authentication.logout.LogoutFilter",
            "org.springframework.security.web.authentication.AuthenticationFilter",
            "org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter",
            "org.springframework.security.web.csrf.CsrfFilter",
            "org.springframework.security.web.session.SessionManagementFilter",
            "org.springframework.security.web.session.ConcurrentSessionFilter",
            "org.springframework.security.web.session.DisableEncodeUrlFilter",
            "org.springframework.security.web.access.ExceptionTranslationFilter",
            "org.springframework.security.web.savedrequest.RequestCacheAwareFilter",
            "org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter",
            "org.springframework.security.web.header.HeaderWriterFilter",
            "org.springframework.security.web.transport.HttpsRedirectFilter",
            "org.springframework.security.web.access.channel.ChannelProcessingFilter",
            "org.springframework.security.web.debug.DebugFilter",
            "org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy",
            "org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy",
            "org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy",
            "org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy",
            "org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy",
            "org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy",
            "org.springframework.security.web.csrf.CsrfAuthenticationStrategy",
            "org.springframework.security.web.header.writers.HstsHeaderWriter",
            "org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter",
            "org.springframework.security.web.header.writers.XXssProtectionHeaderWriter",
            "org.springframework.security.web.header.writers.CacheControlHeadersWriter",
            "org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter",
            "org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.CrossOriginEmbedderPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter",
            "org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter",
            "org.springframework.security.oauth2.server.resource.web.OAuth2ProtectedResourceMetadataFilter",
            "org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter",
            "org.springframework.security.oauth2.client.web.OAuth2AuthorizationCodeGrantFilter",
            "org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter");

    private static MatcherFacts unknownMatcher() {
        return new MatcherFacts("unknown", null, null, List.of());
    }

    private static MatcherFacts matcherFacts(Object matcher, int depth) {
        return matcherFacts(matcher, depth, new int[] {512});
    }

    private static MatcherFacts matcherFacts(Object matcher, int depth, int[] remaining) {
        if (matcher == null || depth > 8 || --remaining[0] < 0) return unknownMatcher();
        String name = matcher.getClass().getName();
        if (matcher.getClass() == AnyRequestMatcher.class) return new MatcherFacts("any", null, null, List.of());
        if (name.equals("org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher")) {
            Object pattern = readField(matcher, "pattern");
            Object method = readField(matcher, "method");
            String methodName = null;
            if (method != AnyRequestMatcher.INSTANCE) {
                if (method == null
                        || !method.getClass()
                                .getName()
                                .equals(
                                        "org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher$HttpMethodRequestMatcher")) {
                    return unknownMatcher();
                }
                Object httpMethod = readField(method, "method");
                if (!(httpMethod instanceof org.springframework.http.HttpMethod typed)) return unknownMatcher();
                methodName = typed.name();
            }
            if (pattern != null && pattern.getClass() == org.springframework.web.util.pattern.PathPattern.class) {
                var typed = (org.springframework.web.util.pattern.PathPattern) pattern;
                if (!Boolean.TRUE.equals(readField(typed, "caseSensitive"))
                        || readField(typed, "pathOptions")
                                != org.springframework.http.server.PathContainer.Options.HTTP_PATH) {
                    return unknownMatcher();
                }
                if (typed.getPatternString().length() > 2048) return unknownMatcher();
                return new MatcherFacts("path", methodName, typed.getPatternString(), List.of());
            }
        }
        String kind = name.equals("org.springframework.security.web.util.matcher.OrRequestMatcher")
                ? "or"
                : name.equals("org.springframework.security.web.util.matcher.AndRequestMatcher") ? "and" : null;
        if (kind != null
                && readField(matcher, "requestMatchers") instanceof List<?> children
                && children.size() <= 128) {
            return new MatcherFacts(
                    kind,
                    null,
                    null,
                    children.stream()
                            .map(child -> matcherFacts(child, depth + 1, remaining))
                            .toList());
        }
        if (name.equals("org.springframework.security.web.util.matcher.NegatedRequestMatcher")) {
            return new MatcherFacts(
                    "not",
                    null,
                    null,
                    List.of(matcherFacts(readField(matcher, "requestMatcher"), depth + 1, remaining)));
        }
        return unknownMatcher();
    }

    private static List<AuthorizationMapping> authorizationMappings(AuthorizationManager<HttpServletRequest> manager) {
        Object unwrapped = unwrapObservationAuthorizationManager(manager);
        if (unwrapped == null) return List.of();
        if (unwrapped.getClass() == SingleResultAuthorizationManager.class) {
            return List.of(
                    new AuthorizationMapping(new MatcherFacts("any", null, null, List.of()), constantGrant(unwrapped)));
        }
        if (unwrapped.getClass() != RequestMatcherDelegatingAuthorizationManager.class
                || !(readField(unwrapped, "mappings") instanceof List<?> mappings)
                || mappings.size() > 512) return List.of();
        List<AuthorizationMapping> result = new ArrayList<>();
        for (Object value : mappings) {
            if (!(value instanceof RequestMatcherEntry<?> entry)) return List.of();
            result.add(new AuthorizationMapping(
                    matcherFacts(entry.getRequestMatcher(), 0), constantGrant(entry.getEntry())));
        }
        return List.copyOf(result);
    }

    private static Boolean constantGrant(Object manager) {
        if (manager == null || manager.getClass() != SingleResultAuthorizationManager.class) return null;
        Object result = readField(manager, "result");
        if (result == null
                || result.getClass() != org.springframework.security.authorization.AuthorizationDecision.class) {
            return null;
        }
        Object granted = readField(result, "granted");
        return granted instanceof Boolean decision ? decision : null;
    }

    private static Boolean blanketGrant(List<AuthorizationMapping> mappings) {
        for (AuthorizationMapping mapping : mappings) {
            if (mapping.grant() == null || !mapping.matcher().complete()) return null;
            if (!mapping.grant()) return false;
            if (mapping.matcher().unconditional()) return true;
        }
        return null;
    }

    static Boolean grantFor(List<AuthorizationMapping> mappings, String method, String path) {
        for (AuthorizationMapping mapping : mappings) {
            Boolean matches = mapping.matcher().matches(method, path);
            if (matches == null) return null;
            if (matches) return mapping.grant();
        }
        return null;
    }

    private static Boolean bearerSavesSession(List<Filter> filters) {
        for (Filter filter : filters) {
            if ("BearerTokenAuthenticationFilter".equals(frameworkTypeName(filter))) {
                Object repository = readField(filter, "securityContextRepository");
                Boolean stateless =
                        repository instanceof SecurityContextRepository typed ? statelessVerdict(typed, 0) : null;
                return stateless == null ? null : !stateless;
            }
        }
        return null;
    }

    private static List<Object> existingSingletons(ListableBeanFactory beanFactory, Class<?> type) {
        if (!(beanFactory instanceof SingletonBeanRegistry registry)) return List.of();
        List<Object> result = new ArrayList<>();
        String[] names = registry.getSingletonNames();
        for (int i = 0; i < Math.min(names.length, MAX_BEAN_SCAN); i++) {
            Object bean = registry.getSingleton(names[i]);
            if (type.isInstance(bean)) result.add(bean);
        }
        return result;
    }

    private static List<Object> activeProviders(List<Filter> filters) {
        List<Object> result = new ArrayList<>();
        for (Filter filter : filters) {
            if ("Unknown".equals(frameworkTypeName(filter))) continue;
            collectProviders(readField(filter, "authenticationManager"), result, 0);
            Object resolver = readField(filter, "authenticationManagerResolver");
            if (resolver != null
                    && resolver.getClass().isSynthetic()
                    && (resolver.getClass()
                                    .getName()
                                    .startsWith(
                                            "org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter$$Lambda")
                            || resolver.getClass()
                                    .getName()
                                    .startsWith(
                                            "org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer$$Lambda"))
                    && resolver.getClass().getDeclaredFields().length == 1) {
                collectProviders(readField(resolver, "arg$1"), result, 0);
            }
        }
        return result;
    }

    private static void collectProviders(Object manager, List<Object> providers, int depth) {
        if (manager != null
                && manager.getClass()
                        .getName()
                        .equals("org.springframework.security.authentication.ObservationAuthenticationManager")
                && depth <= 8) {
            collectProviders(readField(manager, "delegate"), providers, depth + 1);
            return;
        }
        if (manager == null || manager.getClass() != ProviderManager.class || depth > 8 || providers.size() > 512)
            return;
        if (readField(manager, "providers") instanceof List<?> list && list.size() <= 128) {
            for (Object provider : list)
                if (providers.stream().noneMatch(existing -> existing == provider)) providers.add(provider);
        }
        collectProviders(readField(manager, "parent"), providers, depth + 1);
    }

    private static List<PasswordEncoderModel> discoverPasswordEncoders(List<Object> providers) {
        List<PasswordEncoderModel> result = new ArrayList<>();
        for (Object provider : providers) {
            if (!provider.getClass()
                    .getName()
                    .equals("org.springframework.security.authentication.dao.DaoAuthenticationProvider")) continue;
            Object supplier = readField(provider, "passwordEncoder");
            Object encoder = readField(supplier, "singletonInstance");
            Object defaultSupplier = readField(supplier, "instanceSupplier");
            if (encoder == null
                    && supplier != null
                    && supplier.getClass().getName().equals("org.springframework.util.function.SingletonSupplier")
                    && defaultSupplier != null
                    && defaultSupplier.getClass().isSynthetic()
                    && defaultSupplier
                            .getClass()
                            .getName()
                            .startsWith(
                                    "org.springframework.security.authentication.dao.DaoAuthenticationProvider$$Lambda")
                    && defaultSupplier.getClass().getDeclaredFields().length == 0) {
                result.add(new PasswordEncoderModel(
                        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder", 10));
                continue;
            }
            if (!(encoder instanceof PasswordEncoder)
                    && supplier != null
                    && supplier.getClass().isSynthetic()
                    && supplier.getClass()
                            .getName()
                            .startsWith(
                                    "org.springframework.security.authentication.dao.DaoAuthenticationProvider$$Lambda")) {
                encoder = readField(supplier, "arg$1");
            }
            if (encoder != null
                    && encoder.getClass()
                            .getName()
                            .equals("org.springframework.security.crypto.password.DelegatingPasswordEncoder")) {
                encoder = readField(encoder, "passwordEncoderForEncode");
            }
            if (encoder instanceof PasswordEncoder
                    && KNOWN_ENCODERS.contains(encoder.getClass().getName())) {
                result.add(new PasswordEncoderModel(encoder.getClass().getName(), bcryptStrength(encoder)));
            } else {
                result.add(new PasswordEncoderModel("Unknown active provider encoder", null));
            }
        }
        return List.copyOf(result);
    }

    private static final Set<String> KNOWN_ENCODERS = Set.of(
            "org.springframework.security.crypto.password.NoOpPasswordEncoder",
            "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
            "org.springframework.security.crypto.argon2.Argon2PasswordEncoder",
            "org.springframework.security.crypto.password.Pbkdf2PasswordEncoder",
            "org.springframework.security.crypto.scrypt.SCryptPasswordEncoder",
            "org.springframework.security.crypto.password.StandardPasswordEncoder",
            "org.springframework.security.crypto.password.MessageDigestPasswordEncoder",
            "org.springframework.security.crypto.password.Md4PasswordEncoder",
            "org.springframework.security.crypto.password.LdapShaPasswordEncoder");

    private static boolean firewallWeakened(Object firewall) {
        return firewall != null
                && firewall.getClass() == StrictHttpFirewall.class
                && readField(firewall, "encodedUrlBlocklist") instanceof Set<?> blocked
                && FIREWALL_DEFAULT_BLOCKED_TOKENS.stream().anyMatch(token -> !blocked.contains(token));
    }

    private static boolean unconditionalHttpsRedirect(List<Filter> filters) {
        for (Filter filter : filters) {
            if (!filter.getClass().getName().equals("org.springframework.security.web.transport.HttpsRedirectFilter"))
                continue;
            Object mapper = readField(filter, "portMapper");
            Object redirect = readField(filter, "redirectStrategy");
            if (mapper != null
                    && mapper.getClass().getName().equals("org.springframework.security.web.PortMapperImpl")
                    && Map.of(80, 443, 8080, 8443).equals(readField(mapper, "httpsPortMappings"))
                    && redirect != null
                    && redirect.getClass().getName().equals("org.springframework.security.web.DefaultRedirectStrategy")
                    && Boolean.FALSE.equals(readField(redirect, "contextRelative"))
                    && readField(redirect, "statusCode") instanceof org.springframework.http.HttpStatus status
                    && Set.of(
                                    org.springframework.http.HttpStatus.MOVED_PERMANENTLY,
                                    org.springframework.http.HttpStatus.FOUND,
                                    org.springframework.http.HttpStatus.SEE_OTHER,
                                    org.springframework.http.HttpStatus.TEMPORARY_REDIRECT,
                                    org.springframework.http.HttpStatus.PERMANENT_REDIRECT)
                            .contains(status)
                    && matcherFacts(readField(filter, "requestMatcher"), 0).unconditional()) return true;
        }

        return false;
    }

    private static boolean filterMetadataKnown(List<Filter> filters) {
        for (Filter filter : filters) {
            String name = frameworkTypeName(filter);
            if (name.equals("Unknown")) return false;
            if (name.equals("CsrfFilter")) {
                Object matcher = readField(filter, "requireCsrfProtectionMatcher");
                if (matcher == null
                        || !matcher.getClass()
                                .getName()
                                .equals("org.springframework.security.web.csrf.CsrfFilter$DefaultRequiresCsrfMatcher"))
                    return false;
            }
        }
        return true;
    }

    private static SecurityContext.Evidence discoverEvidence(
            ListableBeanFactory beanFactory, List<Object> providers, Environment environment, List<String> errors) {
        List<SecurityContext.Operation> operations = new ArrayList<>();
        boolean operationsKnown = false;
        Set<String> enabled = new java.util.LinkedHashSet<>();
        Set<String> used = new java.util.LinkedHashSet<>();
        boolean methodKnown = true;
        boolean bootJwt = false;
        if (beanFactory instanceof ConfigurableListableBeanFactory configurable) {
            String[] definitions = configurable.getBeanDefinitionNames();
            if (definitions.length > MAX_BEAN_SCAN) {
                methodKnown = false;
                errors.add("Bean metadata inventory limit reached.");
            }
            for (int index = 0; index < Math.min(definitions.length, MAX_BEAN_SCAN); index++) {
                String name = definitions[index];
                var definition = configurable.getBeanDefinition(name);
                String factory = definition.getFactoryBeanName();
                String className = definition.getBeanClassName();
                String configuration = factory != null ? factory : className;
                if (configuration != null) {
                    String prefix = "org.springframework.security.config.annotation.method.configuration.";
                    if (configuration.equals(prefix + "PrePostMethodSecurityConfiguration")) enabled.add("pre-post");
                    if (configuration.equals(prefix + "SecuredMethodSecurityConfiguration")) enabled.add("secured");
                    if (configuration.equals(prefix + "Jsr250MethodSecurityConfiguration")) enabled.add("jsr250");
                }
                if (configurable.containsSingleton(name)) {
                    Object singleton = configurable.getSingleton(name);
                    Object factoryBean = factory == null ? null : configurable.getSingleton(factory);
                    String factoryMethod = definition.getFactoryMethodName();
                    if (singleton != null
                            && (singleton
                                            .getClass()
                                            .getName()
                                            .equals("org.springframework.security.oauth2.jwt.NimbusJwtDecoder")
                                    || singleton
                                            .getClass()
                                            .getName()
                                            .equals("org.springframework.security.oauth2.jwt.SupplierJwtDecoder"))
                            && factoryBean != null
                            && factoryBean
                                    .getClass()
                                    .getName()
                                    .equals(
                                            "org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwtDecoderConfiguration")
                            && factoryMethod != null
                            && Set.of("jwtDecoderByPublicKeyValue", "jwtDecoderByJwkKeySetUri", "jwtDecoderByIssuerUri")
                                    .contains(factoryMethod)
                            && providers.stream().anyMatch(provider -> readField(provider, "jwtDecoder") == singleton))
                        bootJwt = true;
                }
                Class<?> type = className == null ? null : classForName(className);
                if (type == null && configurable.containsSingleton(name)) {
                    Object singleton = configurable.getSingleton(name);
                    if (singleton != null) type = singleton.getClass();
                }
                if (type == null || type.getName().startsWith("org.springframework.")) continue;
                methodKnown &= collectMethodFamilies(type, used, new java.util.HashSet<>(), 0);
            }
        } else methodKnown = false;
        Class<?> advisorType = classForName("org.springframework.security.authorization.method.AuthorizationAdvisor");
        if (advisorType != null) {
            for (Object advisor : existingSingletons(beanFactory, advisorType)) {
                String type = advisor.getClass().getName();
                if (type.equals(
                                "org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor")
                        || type.equals(
                                "org.springframework.security.authorization.method.AuthorizationManagerAfterMethodInterceptor")) {
                    Object manager = readField(advisor, "authorizationManager");
                    if (manager == null) {
                        methodKnown = false;
                        continue;
                    }
                    String name = manager.getClass().getName();
                    if (name.equals("org.springframework.security.authorization.method.SecuredAuthorizationManager"))
                        enabled.add("secured");
                    else if (name.equals(
                            "org.springframework.security.authorization.method.Jsr250AuthorizationManager"))
                        enabled.add("jsr250");
                    else if (name.equals(
                                    "org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager")
                            || name.equals(
                                    "org.springframework.security.authorization.method.PostAuthorizeAuthorizationManager"))
                        enabled.add("pre-post");
                    else methodKnown = false;
                } else if (type.equals(
                                "org.springframework.security.authorization.method.PreFilterAuthorizationMethodInterceptor")
                        || type.equals(
                                "org.springframework.security.authorization.method.PostFilterAuthorizationMethodInterceptor")) {
                    enabled.add("pre-post");
                } else if (!type.equals(
                                "org.springframework.security.authorization.method.AuthorizeReturnObjectMethodInterceptor")
                        && !type.equals(
                                "org.springframework.security.config.annotation.method.configuration.DeferringMethodInterceptor")) {
                    methodKnown = false;
                }
            }
        }
        try {
            String servletPath = environment.getProperty("spring.mvc.servlet.path");
            if (!SecurityActuatorObservation.observe(environment).separateManagementPort()
                    && (servletPath == null || servletPath.isBlank() || servletPath.equals("/"))) {
                for (Object singleton : existingSingletons(beanFactory, Object.class)) {
                    if (!singleton
                            .getClass()
                            .getName()
                            .equals(
                                    "org.springframework.boot.webmvc.actuate.endpoint.web.WebMvcEndpointHandlerMapping"))
                        continue;
                    Object endpointMapping = readField(singleton, "endpointMapping");
                    Object prefix = readField(endpointMapping, "path");
                    Object endpoints = readField(singleton, "endpoints");
                    if (!(prefix instanceof String base)
                            || !(endpoints instanceof java.util.Collection<?> inventory)
                            || inventory.size() > 256) {
                        errors.add("Actuator operation inventory is unsupported.");
                        continue;
                    }
                    operationsKnown = true;
                    for (Object endpoint : inventory) {
                        if (endpoint == null
                                || !endpoint.getClass()
                                        .getName()
                                        .equals(
                                                "org.springframework.boot.actuate.endpoint.web.annotation.DiscoveredWebEndpoint")) {
                            operationsKnown = false;
                            continue;
                        }
                        Object id = readField(readField(endpoint, "id"), "value");
                        Object defaultAccess = readField(endpoint, "defaultAccess");
                        Object raw = readField(endpoint, "operations");
                        if (!(id instanceof String endpointId)
                                || !(raw instanceof List<?> endpointOperations)
                                || endpointOperations.size() > 256) {
                            operationsKnown = false;
                            continue;
                        }
                        for (Object operation : endpointOperations) {
                            if (operations.size() >= 1024
                                    || operation == null
                                    || !operation
                                            .getClass()
                                            .getName()
                                            .equals(
                                                    "org.springframework.boot.actuate.endpoint.web.annotation.DiscoveredWebOperation")) {
                                operationsKnown = false;
                                continue;
                            }
                            Object predicate = readField(operation, "requestPredicate");
                            Object path = readField(predicate, "path");
                            Object method = readField(predicate, "httpMethod");
                            if (!(path instanceof String operationPath) || !(method instanceof Enum<?> httpMethod)) {
                                operationsKnown = false;
                                continue;
                            }
                            if (operationPath.contains("{") || operationPath.contains("*")) {
                                operationsKnown = false;
                                continue;
                            }
                            if (!(defaultAccess instanceof Enum<?> access)) {
                                operationsKnown = false;
                                continue;
                            }
                            operations.add(new SecurityContext.Operation(
                                    endpointId,
                                    httpMethod.name(),
                                    (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                                            + (operationPath.startsWith("/") ? "" : "/")
                                            + operationPath,
                                    access.name()));
                        }
                    }
                }
            }
        } catch (SecurityActuatorObservation.ObservationLimitException ex) {
            operationsKnown = false;
            errors.add("Actuator configuration evidence is incomplete.");
        }
        return new SecurityContext.Evidence(operations, operationsKnown, bootJwt, enabled, used, methodKnown);
    }

    private static final Map<String, List<String>> METHOD_ANNOTATIONS = Map.of(
            "pre-post",
                    List.of(
                            "org.springframework.security.access.prepost.PreAuthorize",
                            "org.springframework.security.access.prepost.PostAuthorize",
                            "org.springframework.security.access.prepost.PreFilter",
                            "org.springframework.security.access.prepost.PostFilter"),
            "secured", List.of("org.springframework.security.access.annotation.Secured"),
            "jsr250",
                    List.of(
                            "jakarta.annotation.security.RolesAllowed",
                            "jakarta.annotation.security.DenyAll",
                            "jakarta.annotation.security.PermitAll"));

    private static boolean collectMethodFamilies(
            Class<?> type, Set<String> families, Set<Class<?>> visited, int depth) {
        if (type == null || type == Object.class || !visited.add(type)) return true;
        if (depth > 8 || visited.size() > 128) return false;
        try {
            boolean complete = collectAnnotationFamilies(type, families, 0);
            Method[] methods = type.getDeclaredMethods();
            if (methods.length > 2000) return false;
            for (Method method : methods) complete &= collectAnnotationFamilies(method, families, 0);
            for (Class<?> contract : type.getInterfaces())
                complete &= collectMethodFamilies(contract, families, visited, depth + 1);
            return collectMethodFamilies(type.getSuperclass(), families, visited, depth + 1) && complete;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean collectAnnotationFamilies(
            java.lang.reflect.AnnotatedElement element, Set<String> families, int depth) {
        return collectAnnotationFamilies(element, families, depth, new java.util.HashSet<>());
    }

    private static boolean collectAnnotationFamilies(
            java.lang.reflect.AnnotatedElement element, Set<String> families, int depth, Set<Class<?>> visited) {
        if (depth > 8 || visited.size() > 128) return false;
        var annotations = element.getDeclaredAnnotations();
        if (annotations.length > 128) return false;
        boolean complete = true;
        for (var annotation : annotations) {
            Class<?> type = annotation.annotationType();
            for (var family : METHOD_ANNOTATIONS.entrySet()) {
                if (family.getValue().contains(type.getName())) families.add(family.getKey());
            }
            if (!type.getName().startsWith("java.lang.annotation.") && visited.add(type)) {
                complete &= collectAnnotationFamilies(type, families, depth + 1, visited);
            }
        }
        return complete;
    }

    private static CorsDiscoveryResult discoverAttachedCors(
            List<FilterChainModel> chains,
            Map<Integer, List<Filter>> chainFilters,
            List<CorsConfigModel> configs,
            List<String> errors) {
        boolean present = false;
        boolean unknown = false;
        List<MatcherFacts> earlierChains = new ArrayList<>();
        for (FilterChainModel owner : chains) {
            List<Filter> filters = chainFilters.getOrDefault(owner.index(), List.of());
            for (Filter filter : filters) {
                if (filter.getClass() != CorsFilter.class) continue;
                present = true;
                if (filters.stream().filter(CorsFilter.class::isInstance).count() != 1
                        || !owner.details().filtersKnown()) {
                    unknown = true;
                    continue;
                }
                Object source = readField(filter, "configSource");
                if (source == null || source.getClass() != UrlBasedCorsConfigurationSource.class) {
                    unknown = true;
                    continue;
                }
                Object processor = readField(filter, "processor");
                if (processor == null
                        || processor.getClass() != org.springframework.web.cors.DefaultCorsProcessor.class
                        || readField(source, "pathMatcher") != readField(source, "defaultPathMatcher")
                        || readField(source, "urlPathHelper")
                                != org.springframework.web.util.UrlPathHelper.defaultInstance
                        || !Boolean.TRUE.equals(readField(source, "allowInitLookupPath"))) {
                    unknown = true;
                    continue;
                }
                Object mappings = readField(source, "corsConfigurations");
                if (!(mappings instanceof java.util.LinkedHashMap<?, ?> map)
                        || map.getClass() != java.util.LinkedHashMap.class
                        || map.size() > 512) {
                    unknown = true;
                    continue;
                }
                List<MatcherFacts> earlierMappings = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (configs.size() >= 512) {
                        unknown = true;
                        break;
                    }
                    MatcherFacts mapping = corsMatcher(entry.getKey());
                    Boolean applicable =
                            corsApplicable(owner.details().matcher(), mapping, earlierChains, earlierMappings);
                    earlierMappings.add(mapping);
                    if (Boolean.FALSE.equals(applicable)) continue;
                    if (applicable == null) {
                        unknown = true;
                        continue;
                    }
                    if (entry.getValue() == null || entry.getValue().getClass() != CorsConfiguration.class) {
                        unknown = true;
                        continue;
                    }
                    CorsConfiguration config = (CorsConfiguration) entry.getValue();
                    if (!boundedCorsValues(config.getAllowedOrigins())
                            || !boundedCorsValues(config.getAllowedOriginPatterns())
                            || !boundedCorsValues(config.getAllowedMethods())
                            || !boundedCorsValues(config.getAllowedHeaders())) {
                        unknown = true;
                        continue;
                    }
                    configs.add(new CorsConfigModel(
                            mapping.path(),
                            config.getAllowedOrigins(),
                            config.getAllowedOriginPatterns(),
                            config.getAllowedMethods(),
                            config.getAllowedHeaders(),
                            config.getAllowCredentials(),
                            owner.index()));
                }
            }
            earlierChains.add(owner.details().matcher());
        }
        if (unknown) errors.add("Attached CORS metadata is unsupported.");
        return new CorsDiscoveryResult(present, unknown);
    }

    private static MatcherFacts corsMatcher(Object key) {
        if (key != null
                && key.getClass() == org.springframework.web.util.pattern.PathPattern.class
                && Boolean.TRUE.equals(readField(key, "caseSensitive"))
                && readField(key, "pathOptions") == org.springframework.http.server.PathContainer.Options.HTTP_PATH) {
            String path = ((org.springframework.web.util.pattern.PathPattern) key).getPatternString();
            if (path.length() <= 2048) return new MatcherFacts("path", null, path, List.of());
        }
        return unknownMatcher();
    }

    private static Boolean corsApplicable(
            MatcherFacts owner,
            MatcherFacts mapping,
            List<MatcherFacts> earlierChains,
            List<MatcherFacts> earlierMappings) {
        PathRegion chainRegion = PathRegion.of(owner);
        PathRegion mappingRegion = PathRegion.of(mapping);
        if (chainRegion == null || mappingRegion == null) return null;
        PathRegion intersection = chainRegion.intersect(mappingRegion);
        if (intersection == null) return false;
        List<MatcherFacts> earlier = new ArrayList<>(earlierChains);
        earlier.addAll(earlierMappings);
        String ownerMethod = singleMatcher(owner).method();
        for (MatcherFacts prior : earlier) {
            PathRegion region = PathRegion.of(prior);
            MatcherFacts effectivePrior = singleMatcher(prior);
            if (region != null
                    && region.covers(intersection)
                    && (effectivePrior.method() == null
                            || effectivePrior.method().equals(ownerMethod))
                    && Boolean.TRUE.equals(
                            prior.matches(ownerMethod == null ? "GET" : ownerMethod, intersection.path()))) {
                return false;
            }
        }
        boolean unknown = false;
        int candidates = intersection.prefix() ? earlier.size() + 2 : 1;
        List<String> methods = ownerMethod == null
                ? List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                : List.of(ownerMethod);
        for (String method : methods) {
            for (int index = 0; index < candidates; index++) {
                String path = index == 0 ? intersection.path() : intersection.path() + "/__bootui_observation_" + index;
                Boolean owned = owner.matches(method, path);
                if (!Boolean.TRUE.equals(owned)) {
                    unknown |= owned == null;
                    continue;
                }
                boolean reachable = true;
                for (MatcherFacts prior : earlier) {
                    Boolean matches = prior == null ? null : prior.matches(method, path);
                    if (matches == null) unknown = true;
                    if (!Boolean.FALSE.equals(matches)) {
                        reachable = false;
                        break;
                    }
                }
                if (reachable) return true;
            }
        }
        // A finite set of method witnesses cannot exhaust a method-agnostic chain's domain.
        return unknown || intersection.prefix() || ownerMethod == null ? null : false;
    }

    private static MatcherFacts singleMatcher(MatcherFacts matcher) {
        while (matcher != null
                && matcher.children().size() == 1
                && ("and".equals(matcher.kind()) || "or".equals(matcher.kind()))) {
            matcher = matcher.children().get(0);
        }
        return matcher;
    }

    private record PathRegion(String path, boolean prefix) {
        static PathRegion of(MatcherFacts matcher) {
            if (matcher == null) return null;
            if (("or".equals(matcher.kind()) || "and".equals(matcher.kind()))
                    && matcher.children().size() == 1) {
                return of(matcher.children().get(0));
            }
            if (matcher.unconditional()) return new PathRegion("", true);
            if (!"path".equals(matcher.kind()) || !matcher.complete()) return null;
            String path = matcher.path();
            return path.endsWith("/**")
                    ? new PathRegion(path.substring(0, path.length() - 3), true)
                    : new PathRegion(path, false);
        }

        boolean covers(PathRegion other) {
            return prefix
                    ? path.isEmpty()
                            || path.equals(other.path())
                            || other.path().startsWith(path + "/")
                    : !other.prefix() && path.equals(other.path());
        }

        PathRegion intersect(PathRegion other) {
            if (covers(other)) return other;
            if (other.covers(this)) return this;
            return null;
        }
    }

    private static boolean boundedCorsValues(List<String> values) {
        return values == null
                || values.size() <= 512 && values.stream().allMatch(value -> value != null && value.length() <= 4096);
    }

    // ── Aggregation ──────────────────────────────────────────────────────────────

    private List<SecuritySeverityCountDto> severityCounts(List<SecurityRuleResultDto> results) {
        Map<String, Integer> counts = SeverityOrder.occurrenceCounts(
                SEVERITIES,
                results,
                SecurityScanner::isViolation,
                SecurityRuleResultDto::severity,
                SecurityRuleResultDto::violationCount);
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
            if (errors.size() > 20) {
                List<String> bounded = new ArrayList<>(errors.subList(0, 20));
                bounded.add("Additional incomplete observations omitted.");
                errors = List.copyOf(bounded);
            } else errors = List.copyOf(errors);
        }

        static SecurityDiscovery empty(String reason) {
            return new SecurityDiscovery(null, List.of(reason == null ? "Unavailable." : reason));
        }
    }
}
