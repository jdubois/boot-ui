package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.config.BootUiContributedProperties;
import io.github.jdubois.bootui.engine.reactivesecurity.CorsConfigObservation;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityEnvironmentSnapshot;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityObservation;
import io.github.jdubois.bootui.engine.reactivesecurity.WebFilterChainObservation;
import io.github.jdubois.bootui.engine.reactivesecurity.WebFilterChainObservation.CspObservation;
import io.github.jdubois.bootui.engine.security.CspPolicy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.server.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.server.WebFilter;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Passive, bounded inspection of already-instantiated reactive security chains. Custom matchers,
 * converters, writers, CORS sources, bean factories and reactive publishers are never executed.
 * Unsupported metadata remains incomplete rather than proving a missing security control.
 */
public final class SpringReactiveSecurityObservationCollector {

    private static final String BOOT_UI_CHAIN_BEAN_NAME = "bootUiReactiveSecurityWebFilterChain";
    private static final String SECURITY = "org.springframework.security.web.server.";
    private static final String HEADERS = SECURITY + "header.";
    private static final String MATCHERS = SECURITY + "util.matcher.";
    private static final String CORS = "org.springframework.web.cors.reactive.";
    private static final String OAUTH = "org.springframework.security.oauth2.";
    private static final String OIDC_LOGIN_FILTER =
            "org.springframework.security.config.web.server.ServerHttpSecurity$OAuth2LoginSpec$OidcSessionRegistryAuthenticationWebFilter";
    private static final int MAX_CHAINS = 128;
    private static final int MAX_ITEMS = 256;
    private static final int MAX_PROPERTIES = 4096;
    private static final int MAX_TEXT = 4096;
    private static final int MAX_DEPTH = 8;
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(?:^|.*[.\\[\\]-])(?:password|passwd|secret|secret-key|token|api-key|apikey|client-secret|private-key)$");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,200}");
    private static final List<String> FILTER_TYPES = List.of(
            SECURITY + "authorization.AuthorizationWebFilter",
            SECURITY + "csrf.CsrfWebFilter",
            HEADERS + "HttpHeaderWriterWebFilter",
            SECURITY + "transport.HttpsRedirectWebFilter",
            SECURITY + "authentication.AuthenticationWebFilter",
            SECURITY + "authentication.AnonymousAuthenticationWebFilter",
            SECURITY + "authentication.logout.LogoutWebFilter",
            SECURITY + "context.SecurityContextServerWebExchangeWebFilter",
            OAUTH + "client.web.server.authentication.OAuth2LoginAuthenticationWebFilter",
            OIDC_LOGIN_FILTER,
            OAUTH + "client.web.server.OAuth2AuthorizationCodeGrantWebFilter");

    private final ObjectProvider<ListableBeanFactory> beanFactories;
    private final Environment environment;

    public SpringReactiveSecurityObservationCollector(
            ObjectProvider<SecurityWebFilterChain> filterChainProvider,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment) {
        // The provider's orderedStream creates lazy/factory beans. Discover singleton metadata instead.
        this.beanFactories = beanFactories;
        this.environment = environment;
    }

    public ReactiveSecurityObservation collect() {
        List<String> messages = new ArrayList<>();
        ListableBeanFactory factory = beanFactories.getIfAvailable();
        if (factory instanceof ConfigurableApplicationContext context) {
            factory = context.getBeanFactory();
        }
        List<ChainBean> beans = applicationOwnedChains(factory, messages);
        List<WebFilterChainObservation> chains = new ArrayList<>();
        List<CorsConfigObservation> cors = new ArrayList<>();
        CorsState corsState = new CorsState();
        for (int i = 0; i < beans.size(); i++) {
            ChainBean bean = beans.get(i);
            try {
                chains.add(observeChain(i, bean.chain(), bean.orderKnown(), cors, corsState, messages));
            } catch (RuntimeException | LinkageError ex) {
                messages.add("Chain " + i + ": metadata inspection failed ("
                        + ex.getClass().getName() + ").");
                chains.add(new WebFilterChainObservation(
                        i,
                        "(failed chain)",
                        List.of(),
                        null,
                        false,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        null,
                        List.of(),
                        false,
                        ex.getClass().getName()));
                corsState.complete = false;
            }
        }
        discoverGlobalCors(factory, cors, corsState, messages);
        ReactiveSecurityEnvironmentSnapshot snapshot =
                new EnvironmentObservation(environment).environmentSnapshot(messages);
        return new ReactiveSecurityObservation(
                chains,
                cors,
                corsState.present,
                List.of(),
                List.of(),
                List.of(),
                snapshot,
                messages,
                corsState.complete);
    }

    private record ChainBean(SecurityWebFilterChain chain, int order, boolean orderKnown) {}

    private record BeanCandidate(String name, Object instance) {}

    private record BeanInventory(List<BeanCandidate> candidates, boolean complete) {}

    private static BeanInventory beanInventory(ListableBeanFactory factory, Class<?> expectedType) {
        if (!(factory instanceof SingletonBeanRegistry singletons)) {
            return new BeanInventory(List.of(), false);
        }
        Set<String> names = new LinkedHashSet<>();
        boolean complete = true;
        String[] definitions = factory instanceof ConfigurableListableBeanFactory configurable
                ? configurable.getBeanDefinitionNames()
                : new String[0];
        for (String[] inventory : List.of(definitions, singletons.getSingletonNames())) {
            for (int index = 0; index < inventory.length && index < MAX_PROPERTIES; index++) {
                if (names.size() >= MAX_PROPERTIES && !names.contains(inventory[index])) {
                    complete = false;
                    break;
                }
                names.add(inventory[index]);
            }
            complete &= inventory.length <= MAX_PROPERTIES;
        }
        List<BeanCandidate> candidates = new ArrayList<>();
        for (String name : names) {
            Object instance = singletons.containsSingleton(name) ? singletons.getSingleton(name) : null;
            BeanDefinition definition = factory instanceof ConfigurableListableBeanFactory configurable
                            && configurable.containsBeanDefinition(name)
                    ? configurable.getBeanDefinition(name)
                    : null;
            if (definition != null && definition.isAbstract()) {
                continue;
            }
            if (instance != null && !(instance instanceof FactoryBean<?>)) {
                if (expectedType.isInstance(instance)) {
                    candidates.add(new BeanCandidate(name, instance));
                }
                continue;
            }
            ResolvableType type = instance instanceof FactoryBean<?>
                    ? ResolvableType.forClass(instance.getClass())
                    : declaredType(definition);
            Class<?> resolved = type.resolve();
            if (resolved != null && FactoryBean.class.isAssignableFrom(resolved)) {
                Object productType =
                        definition == null ? null : definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
                type = productType instanceof Class<?> product
                        ? ResolvableType.forClass(product)
                        : productType instanceof ResolvableType product
                                ? product
                                : type.as(FactoryBean.class).getGeneric(0);
                resolved = type.resolve();
            }
            if (resolved == null || resolved == Object.class) {
                complete = false;
            } else if (expectedType.isAssignableFrom(resolved)) {
                // A FactoryBean product is not retrieved, even when its factory already exists.
                candidates.add(new BeanCandidate(name, null));
            }
        }
        return new BeanInventory(List.copyOf(candidates), complete);
    }

    private static ResolvableType declaredType(BeanDefinition definition) {
        if (definition == null) {
            return ResolvableType.NONE;
        }
        ResolvableType type = definition.getResolvableType();
        if (type.resolve() != null) {
            return type;
        }
        String className =
                definition instanceof AnnotatedBeanDefinition annotated && annotated.getFactoryMethodMetadata() != null
                        ? annotated.getFactoryMethodMetadata().getReturnTypeName()
                        : definition.getBeanClassName();
        if (className == null) {
            return ResolvableType.NONE;
        }
        try {
            return ResolvableType.forClass(
                    Class.forName(className, false, SpringReactiveSecurityObservationCollector.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError ex) {
            return ResolvableType.NONE;
        }
    }

    private List<ChainBean> applicationOwnedChains(ListableBeanFactory factory, List<String> messages) {
        if (!(factory instanceof SingletonBeanRegistry)) {
            messages.add("Already-instantiated chain inventory is unavailable; bean providers were not invoked.");
            return List.of();
        }
        List<ChainBean> result = new ArrayList<>();
        BeanInventory inventory = beanInventory(factory, SecurityWebFilterChain.class);
        for (int i = 0; i < inventory.candidates().size() && i < MAX_CHAINS; i++) {
            BeanCandidate candidate = inventory.candidates().get(i);
            String name = candidate.name();
            if (BOOT_UI_CHAIN_BEAN_NAME.equals(name)) {
                continue;
            }
            Object singleton = candidate.instance();
            Integer order = declaredOrder(factory, name, singleton);
            if (singleton instanceof SecurityWebFilterChain chain) {
                result.add(new ChainBean(chain, order == null ? Integer.MAX_VALUE : order, order != null));
            } else {
                // Keep the unknown chain in its declared position, without invoking a lazy or FactoryBean.
                result.add(new ChainBean(null, order == null ? Integer.MAX_VALUE : order, order != null));
                messages.add("A chain definition is not an instantiated singleton; it was not initialized.");
            }
        }
        if (inventory.candidates().size() > MAX_CHAINS) {
            messages.add("Chain inventory limit reached; remaining chain order is unknown.");
            result.replaceAll(bean -> new ChainBean(bean.chain(), bean.order(), false));
        }
        if (!inventory.complete()) {
            ChainBean unknown = new ChainBean(null, Integer.MAX_VALUE, false);
            if (result.size() == MAX_CHAINS) {
                result.set(MAX_CHAINS - 1, unknown);
            } else {
                result.add(unknown);
            }
            messages.add("Bean type inventory is incomplete; FactoryBean product callbacks were not invoked.");
        }
        result.sort(Comparator.comparingInt(ChainBean::order));
        if (result.stream().anyMatch(bean -> !bean.orderKnown())) {
            result.replaceAll(bean -> new ChainBean(bean.chain(), bean.order(), false));
            messages.add("Custom chain ordering is unsupported; catch-all ordering is inconclusive.");
        }
        return result;
    }

    private static Integer declaredOrder(ListableBeanFactory factory, String name, Object singleton) {
        if (singleton instanceof org.springframework.core.Ordered) {
            return null;
        }
        if (factory instanceof ConfigurableListableBeanFactory configurable
                && configurable.containsBeanDefinition(name)) {
            BeanDefinition definition = configurable.getBeanDefinition(name);
            if (definition instanceof AnnotatedBeanDefinition annotated
                    && annotated.getFactoryMethodMetadata() != null) {
                Map<String, Object> values =
                        annotated.getFactoryMethodMetadata().getAnnotationAttributes(Order.class.getName());
                if (values != null && values.get("value") instanceof Integer order) {
                    return order;
                }
            }
            if (definition instanceof RootBeanDefinition root && root.getResolvedFactoryMethod() != null) {
                Order order = root.getResolvedFactoryMethod().getAnnotation(Order.class);
                if (order != null) {
                    return order.value();
                }
            }
        }
        if (singleton != null) {
            Order annotation = singleton.getClass().getAnnotation(Order.class);
            if (annotation != null) {
                return annotation.value();
            }
        }
        return Integer.MAX_VALUE;
    }

    private static WebFilterChainObservation unknownChain(int index) {
        return new WebFilterChainObservation(
                index,
                "(unobserved chain)",
                List.of(),
                null,
                false,
                List.of(),
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                false,
                null,
                List.of(),
                false);
    }

    private static WebFilterChainObservation observeChain(
            int index,
            SecurityWebFilterChain chain,
            boolean orderKnown,
            List<CorsConfigObservation> cors,
            CorsState corsState,
            List<String> messages) {
        if (chain == null || chain.getClass() != MatcherSecurityWebFilterChain.class) {
            messages.add(
                    "Chain " + index + ": custom/uninstantiated chain is unsupported; its publisher was not invoked.");
            corsState.complete = false;
            return unknownChain(index);
        }
        Object matcher = readField(chain, "matcher");
        Boolean unconditional = orderKnown ? unconditionalMatcher(matcher) : null;
        Object rawFilters = readField(chain, "filters");
        if (!(rawFilters instanceof List<?> filters)) {
            messages.add("Chain " + index + ": filter metadata is unavailable.");
            return unknownChain(index);
        }
        boolean filtersComplete = filters.size() <= MAX_ITEMS;
        List<String> names = new ArrayList<>();
        List<Object> headerFilters = new ArrayList<>();
        boolean basic = false;
        boolean form = false;
        boolean bearer = false;
        boolean authComplete = true;
        boolean redirect = false;
        for (int i = 0; i < filters.size() && i < MAX_ITEMS; i++) {
            Object filter = filters.get(i);
            if (!(filter instanceof WebFilter)) {
                filtersComplete = false;
                continue;
            }
            String recognized = FILTER_TYPES.stream()
                    .filter(type -> exact(filter, type))
                    .findFirst()
                    .orElse(null);
            if (recognized == null && FILTER_TYPES.stream().anyMatch(type -> hasSuperclass(filter, type))) {
                filtersComplete = false;
            }
            names.add(
                    recognized == null
                            ? "custom:" + filter.getClass().getName()
                            : filter.getClass().getSimpleName());
            if (exact(filter, HEADERS + "HttpHeaderWriterWebFilter")) {
                headerFilters.add(filter);
            }
            if (exact(filter, SECURITY + "authentication.AuthenticationWebFilter")) {
                Object converter = readField(filter, "authenticationConverter");
                boolean isBasic = exact(converter, SECURITY + "authentication.ServerHttpBasicAuthenticationConverter");
                boolean isForm = exact(converter, SECURITY + "authentication.ServerFormLoginAuthenticationConverter");
                boolean isBearer = exact(
                        converter,
                        OAUTH + "server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter");
                basic |= isBasic;
                form |= isForm;
                bearer |= isBearer;
                authComplete &= isBasic || isForm || isBearer;
            } else if (hasSuperclass(filter, SECURITY + "authentication.AuthenticationWebFilter")
                    && !exact(filter, OAUTH + "client.web.server.authentication.OAuth2LoginAuthenticationWebFilter")
                    && !exact(filter, OIDC_LOGIN_FILTER)) {
                authComplete = false;
            }
            if (exact(filter, SECURITY + "transport.HttpsRedirectWebFilter")) {
                redirect |= Boolean.TRUE.equals(unconditionalMatcher(readField(filter, "requiresHttpsRedirectMatcher")))
                        && exact(readField(filter, "portMapper"), "org.springframework.security.web.PortMapperImpl")
                        && exact(readField(filter, "redirectStrategy"), SECURITY + "DefaultServerRedirectStrategy");
            }
            if (hasSuperclass(filter, CORS + "CorsWebFilter")) {
                discoverCors(filter, cors, corsState, messages);
            }
        }
        if (!filtersComplete) {
            messages.add(
                    "Chain " + index + ": filter inventory limit or unsupported entry; missing controls are unknown.");
            corsState.complete = false;
        }
        if (!authComplete) {
            messages.add("Chain " + index + ": custom authentication converter/filter metadata is unsupported.");
        }
        HeaderInfo headers = headerInfo(headerFilters, index, messages);
        CspObservation legacy = headers.policies.size() == 1 ? headers.policies.get(0) : null;
        return new WebFilterChainObservation(
                index,
                describeMatcher(matcher),
                names,
                filtersComplete ? !names.contains("AuthorizationWebFilter") : null,
                bearer,
                headers.names,
                headers.hstsMaxAge,
                headers.hstsSubdomains,
                legacy == null ? null : legacy.policy(),
                legacy == null ? null : legacy.reportOnly(),
                filtersComplete && headers.complete,
                form,
                basic,
                filtersComplete && authComplete,
                unconditional,
                headers.policies,
                redirect);
    }

    private static Boolean unconditionalMatcher(Object matcher) {
        if (matcher == null) {
            return null;
        }
        if (matcher.getClass() == ServerWebExchangeMatchers.anyExchange().getClass()) {
            return true;
        }
        if (exact(matcher, MATCHERS + "PathPatternParserServerWebExchangeMatcher")
                && readField(matcher, "pattern") instanceof PathPattern pattern) {
            return readField(matcher, "method") == null && "/**".equals(pattern.getPatternString());
        }
        // Custom/composite/negated matchers are not unconditional merely because their text contains /**.
        return null;
    }

    private static String describeMatcher(Object matcher) {
        if (matcher == null) {
            return "(unknown matcher)";
        }
        if (matcher.getClass() == ServerWebExchangeMatchers.anyExchange().getClass()) {
            return "any request";
        }
        if (exact(matcher, MATCHERS + "PathPatternParserServerWebExchangeMatcher")
                && readField(matcher, "pattern") instanceof PathPattern pattern) {
            Object method = readField(matcher, "method");
            String path = pattern.getPatternString();
            return (method instanceof org.springframework.http.HttpMethod http ? http.name() + " " : "")
                    + (path.length() <= MAX_TEXT ? path : "(path exceeds limit)");
        }
        return "(custom matcher: " + matcher.getClass().getName() + ")";
    }

    private static final class HeaderInfo {
        final List<String> names = new ArrayList<>();
        final List<CspObservation> policies = new ArrayList<>();
        final Map<String, Integer> occurrences = new LinkedHashMap<>();
        Long hstsMaxAge;
        Boolean hstsSubdomains;
        boolean complete = true;
        int count;
    }

    private static HeaderInfo headerInfo(List<Object> filters, int index, List<String> messages) {
        HeaderInfo info = new HeaderInfo();
        for (Object filter : filters) {
            inspectWriter(readField(filter, "writer"), 0, info);
        }
        if (info.occurrences.values().stream().anyMatch(count -> count > 1)) {
            info.complete = false;
            // Multiple same-header writers are not a policy union and do not have scalar last-writer semantics.
            if (info.occurrences.getOrDefault("strict-transport-security", 0) > 1) {
                info.hstsMaxAge = null;
            }
        }
        if (!info.complete) {
            messages.add("Chain " + index
                    + ": header scope, policy composition, or writer inventory is unsupported/incomplete.");
        }
        return info;
    }

    private static void inspectWriter(Object writer, int depth, HeaderInfo info) {
        if (writer == null || depth >= MAX_DEPTH || ++info.count > MAX_ITEMS) {
            info.complete = false;
            return;
        }
        if (exact(writer, HEADERS + "CompositeServerHttpHeadersWriter")) {
            if (!(readField(writer, "writers") instanceof List<?> writers)) {
                info.complete = false;
                return;
            }
            for (int i = 0; i < writers.size() && i < MAX_ITEMS; i++) {
                inspectWriter(writers.get(i), depth + 1, info);
            }
            info.complete &= writers.size() <= MAX_ITEMS;
            return;
        }
        String name = writer.getClass().getName();
        if (exact(writer, HEADERS + "StaticServerHttpHeadersWriter")) {
            inspectStaticHeaders(writer, info);
        } else if (exact(writer, HEADERS + "StrictTransportSecurityServerHttpHeadersWriter")) {
            info.names.add("StrictTransportSecurityServerHttpHeadersWriter");
            noteHeader(info, "strict-transport-security");
            info.hstsMaxAge = parseMaxAge(readField(writer, "maxAge"));
            Object subdomain = readField(writer, "subdomain");
            info.hstsSubdomains = subdomain instanceof String value ? value.contains("includeSubDomains") : null;
            info.complete &= info.hstsMaxAge != null;
        } else if (exact(writer, HEADERS + "ContentSecurityPolicyServerHttpHeadersWriter")) {
            Object policy = readField(writer, "policyDirectives");
            Object report = readField(writer, "reportOnly");
            // Spring installs this writer by default with no policy/delegate; it emits no CSP header.
            if (policy == null
                    && report instanceof Boolean
                    && observedNullField(writer, "policyDirectives")
                    && observedNullField(writer, "delegate")) {
                return;
            }
            info.names.add("ContentSecurityPolicyServerHttpHeadersWriter");
            if (policy instanceof String text && report instanceof Boolean reportOnly) {
                info.policies.add(new CspObservation(text.length() <= MAX_TEXT ? text : null, reportOnly));
                noteHeader(info, reportOnly ? "content-security-policy-report-only" : "content-security-policy");
                info.complete &=
                        text.length() <= MAX_TEXT && CspPolicy.analyze(text).complete();
            } else {
                info.complete = false;
            }
        } else if (exact(writer, HEADERS + "XFrameOptionsServerHttpHeadersWriter")) {
            info.names.add("XFrameOptionsServerHttpHeadersWriter");
            noteHeader(info, "x-frame-options");
        } else if (exact(writer, HEADERS + "ContentTypeOptionsServerHttpHeadersWriter")) {
            info.names.add("ContentTypeOptionsServerHttpHeadersWriter");
            noteHeader(info, "x-content-type-options");
        } else if (Set.of(
                        HEADERS + "CacheControlServerHttpHeadersWriter",
                        HEADERS + "XXssProtectionServerHttpHeadersWriter",
                        HEADERS + "ReferrerPolicyServerHttpHeadersWriter",
                        HEADERS + "PermissionsPolicyServerHttpHeadersWriter",
                        HEADERS + "FeaturePolicyServerHttpHeadersWriter",
                        HEADERS + "CrossOriginEmbedderPolicyServerHttpHeadersWriter",
                        HEADERS + "CrossOriginOpenerPolicyServerHttpHeadersWriter",
                        HEADERS + "CrossOriginResourcePolicyServerHttpHeadersWriter")
                .contains(name)) {
            info.names.add(writer.getClass().getSimpleName());
        } else {
            info.names.add("custom:" + name);
            info.complete = false;
        }
    }

    private static void inspectStaticHeaders(Object writer, HeaderInfo info) {
        info.names.add("StaticServerHttpHeadersWriter");
        Object value = readField(writer, "headersToAdd");
        if (!(value instanceof HttpHeaders headers)
                || value.getClass() != HttpHeaders.class
                || headers.size() > MAX_ITEMS) {
            info.complete = false;
            return;
        }
        // StaticServerHttpHeadersWriter skips its entire map if any configured header already exists.
        // A multi-header map therefore has conditional scope that cannot prove individual presence.
        if (headers.size() != 1) {
            info.complete = false;
            return;
        }
        headers.forEach((name, values) -> {
            if (values.size() != 1 || values.get(0) == null || values.get(0).length() > MAX_TEXT) {
                info.complete = false;
                return;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            String text = values.get(0);
            noteHeader(info, lower);
            switch (lower) {
                case "content-security-policy", "content-security-policy-report-only" -> {
                    info.policies.add(new CspObservation(text, lower.endsWith("-report-only")));
                    info.complete &= CspPolicy.analyze(text).complete();
                }
                case "x-frame-options" -> {
                    if ("DENY".equalsIgnoreCase(text) || "SAMEORIGIN".equalsIgnoreCase(text)) {
                        info.names.add("XFrameOptionsServerHttpHeadersWriter");
                    }
                }
                case "x-content-type-options" -> {
                    if ("nosniff".equalsIgnoreCase(text)) {
                        info.names.add("ContentTypeOptionsServerHttpHeadersWriter");
                    }
                }
                case "strict-transport-security" -> {
                    info.names.add("StrictTransportSecurityServerHttpHeadersWriter");
                    String[] directives = text.split(";");
                    info.hstsMaxAge = parseMaxAge(directives[0].trim());
                    info.complete &= info.hstsMaxAge != null;
                }
                default -> {}
            }
        });
    }

    private static void noteHeader(HeaderInfo info, String name) {
        info.occurrences.merge(name, 1, Integer::sum);
    }

    private static Long parseMaxAge(Object raw) {
        if (!(raw instanceof String text) || !text.matches("(?i)max-age=[0-9]{1,18}")) {
            return null;
        }
        return Long.parseLong(text.substring(text.indexOf('=') + 1));
    }

    private static final class CorsState {
        boolean present;
        boolean complete = true;
        final Set<Object> observedFilters = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void discoverGlobalCors(
            ListableBeanFactory factory, List<CorsConfigObservation> result, CorsState state, List<String> messages) {
        BeanInventory inventory = beanInventory(factory, WebFilter.class);
        if (!inventory.complete()) {
            state.complete = false;
            messages.add("Global WebFilter type inventory is incomplete; FactoryBean callbacks were not invoked.");
        }
        for (int index = 0; index < inventory.candidates().size() && index < MAX_ITEMS; index++) {
            Object filter = inventory.candidates().get(index).instance();
            if (filter == null) {
                state.complete = false;
                messages.add("A WebFilter definition is not instantiated; CORS inventory may be incomplete.");
            } else if (hasSuperclass(filter, CORS + "CorsWebFilter")) {
                discoverCors(filter, result, state, messages);
            }
        }
        if (inventory.candidates().size() > MAX_ITEMS) {
            state.complete = false;
            messages.add("Global WebFilter inventory limit reached.");
        }
    }

    private static void discoverCors(
            Object filter, List<CorsConfigObservation> result, CorsState state, List<String> messages) {
        if (!state.observedFilters.add(filter)) {
            return;
        }
        state.present = true;
        Object source = readField(filter, "configSource");
        Object processor = readField(filter, "processor");
        if (!exact(filter, CORS + "CorsWebFilter")
                || !exact(source, CORS + "UrlBasedCorsConfigurationSource")
                || !exact(processor, CORS + "DefaultCorsProcessor")
                || !(readField(source, "corsConfigurations") instanceof Map<?, ?> configurations)) {
            state.complete = false;
            messages.add("Installed CORS filter/source/processor is unsupported; no application callback was invoked.");
            return;
        }
        int count = 0;
        for (Map.Entry<?, ?> entry : configurations.entrySet()) {
            if (++count > MAX_ITEMS || result.size() >= MAX_ITEMS) {
                state.complete = false;
                break;
            }
            if (!(entry.getKey() instanceof PathPattern path)
                    || !(entry.getValue() instanceof CorsConfiguration config)
                    || config.getClass() != CorsConfiguration.class
                    || path.getPatternString().length() > MAX_TEXT
                    || !bounded(config.getAllowedOrigins())
                    || !bounded(config.getAllowedOriginPatterns())
                    || !bounded(config.getAllowedMethods())
                    || !bounded(config.getAllowedHeaders())) {
                state.complete = false;
                continue;
            }
            result.add(new CorsConfigObservation(
                    path.getPatternString(),
                    config.getAllowedOrigins(),
                    config.getAllowedOriginPatterns(),
                    config.getAllowedMethods(),
                    config.getAllowedHeaders(),
                    config.getAllowCredentials()));
        }
        if (!state.complete) {
            messages.add("Installed CORS configuration inventory is incomplete or exceeds its bound.");
        }
    }

    private static boolean bounded(List<String> values) {
        return values == null
                || (values.size() <= MAX_ITEMS
                        && values.stream().allMatch(value -> value != null && value.length() <= MAX_TEXT));
    }

    private static boolean exact(Object object, String type) {
        return object != null && object.getClass().getName().equals(type);
    }

    private static boolean hasSuperclass(Object object, String type) {
        for (Class<?> current = object == null ? null : object.getClass();
                current != null;
                current = current.getSuperclass()) {
            if (current.getName().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean observedNullField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            return field.trySetAccessible() && field.get(target) == null;
        } catch (NoSuchFieldException ex) {
            return false;
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Framework metadata is inaccessible", ex);
        }
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                return field.trySetAccessible() ? field.get(target) : null;
            } catch (NoSuchFieldException ex) {
                // A supported superclass may own this metadata field.
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Framework metadata is inaccessible", ex);
            }
        }
        return null;
    }

    private static final class EnvironmentObservation {
        private final Environment environment;

        private EnvironmentObservation(Environment environment) {
            this.environment = SecurityEnvironmentSnapshot.capture(environment);
        }

        private ReactiveSecurityEnvironmentSnapshot environmentSnapshot(List<String> messages) {
            Map<String, String> failures = new LinkedHashMap<>();
            Set<String> incomplete = new LinkedHashSet<>();
            String issuer = observe(
                    () -> property("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
                    null,
                    failures,
                    incomplete,
                    "SEC-RXF-OAUTH2-002",
                    "SEC-RXF-OAUTH2-003");
            String jwks = observe(
                    () -> property("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
                    null,
                    failures,
                    incomplete,
                    "SEC-RXF-OAUTH2-002",
                    "SEC-RXF-OAUTH2-003");
            String staticKey = observe(
                    () -> property("spring.security.oauth2.resourceserver.jwt.public-key-location"),
                    null,
                    failures,
                    incomplete,
                    "SEC-RXF-OAUTH2-002");
            String introspection = observe(
                    () -> property("spring.security.oauth2.resourceserver.opaquetoken.introspection-uri"),
                    null,
                    failures,
                    incomplete,
                    "SEC-RXF-OAUTH2-004");
            Boolean tls = observe(
                    this::directTlsConfigured, null, failures, incomplete, "SEC-RXF-CONFIG-002", "SEC-RXF-HEAD-001");
            String logging =
                    observe(() -> securityLoggingLevel(messages), null, failures, incomplete, "SEC-RXF-CONFIG-004");
            int beforeSecrets = messages.size();
            Set<String> secrets =
                    observe(() -> suspectedSecrets(messages), Set.of(), failures, incomplete, "SEC-RXF-CONFIG-003");
            if (messages.size() > beforeSecrets) {
                incomplete.add("SEC-RXF-CONFIG-003");
            }
            List<String> profiles = observe(
                    this::activeProfiles,
                    List.of(),
                    failures,
                    incomplete,
                    "SEC-RXF-CONFIG-002",
                    "SEC-RXF-CONFIG-004",
                    "SEC-RXF-OAUTH2-003",
                    "SEC-RXF-OAUTH2-004");
            // Effective Actuator selection is supplied by the security-only Spring helper.
            SecurityActuatorObservation.Snapshot exposure = observe(
                    () -> {
                        return SecurityActuatorObservation.observe(environment);
                    },
                    null,
                    failures,
                    incomplete,
                    "SEC-RXF-ACT-001",
                    "SEC-RXF-ACT-002",
                    "SEC-RXF-ACT-003",
                    "SEC-RXF-ACT-004",
                    "SEC-RXF-ACT-005");
            if (exposure != null) {
                messages.addAll(exposure.errors());
                if (!exposure.complete() && exposure.errors().isEmpty()) {
                    messages.add("Actuator configuration inventory is incomplete or unsupported.");
                    incomplete.addAll(List.of(
                            "SEC-RXF-ACT-001",
                            "SEC-RXF-ACT-002",
                            "SEC-RXF-ACT-003",
                            "SEC-RXF-ACT-004",
                            "SEC-RXF-ACT-005"));
                }
                if (!exposure.errors().isEmpty()) {
                    for (String rule : List.of(
                            "SEC-RXF-ACT-001",
                            "SEC-RXF-ACT-002",
                            "SEC-RXF-ACT-003",
                            "SEC-RXF-ACT-004",
                            "SEC-RXF-ACT-005")) {
                        failures.put(rule, "Invalid or conflicting Actuator configuration.");
                    }
                }
            }
            String envValues = observe(
                    () -> hostProperty("management.endpoint.env.show-values"),
                    null,
                    failures,
                    incomplete,
                    "SEC-RXF-ACT-005");
            String configValues = observe(
                    () -> hostProperty("management.endpoint.configprops.show-values"),
                    null,
                    failures,
                    incomplete,
                    "SEC-RXF-ACT-005");
            return new ReactiveSecurityEnvironmentSnapshot(
                    Boolean.TRUE.equals(tls),
                    exposure == null
                            ? null
                            : exposure.wildcardIncluded() ? "*" : String.join(",", exposure.exposedEndpoints()),
                    null,
                    exposure != null && exposure.separateManagementPort(),
                    profiles,
                    false,
                    staticKey != null && issuer == null && jwks == null && !incomplete.contains("SEC-RXF-OAUTH2-002"),
                    plainHttp(issuer),
                    plainHttp(jwks),
                    logging,
                    secrets,
                    plainHttp(introspection),
                    "always".equalsIgnoreCase(envValues),
                    "always".equalsIgnoreCase(configValues),
                    exposure != null && exposure.exposedEndpoints().contains("env"),
                    exposure != null && exposure.exposedEndpoints().contains("configprops"),
                    exposure == null ? Set.of() : exposure.exposedEndpoints(),
                    exposure != null && exposure.complete(),
                    failures,
                    incomplete,
                    tls != null);
        }

        private static <T> T observe(
                Supplier<T> source,
                T unavailable,
                Map<String, String> failures,
                Set<String> incomplete,
                String... ruleIds) {
            try {
                return source.get();
            } catch (UnsupportedObservation | SecurityActuatorObservation.ObservationLimitException ex) {
                incomplete.addAll(List.of(ruleIds));
                return unavailable;
            } catch (RuntimeException | LinkageError ex) {
                for (String rule : ruleIds) {
                    failures.put(rule, ex.getClass().getName());
                }
                return unavailable;
            }
        }

        private static final class UnsupportedObservation extends RuntimeException {}

        private boolean directTlsConfigured() {
            String enabled = property("server.ssl.enabled");
            if ("false".equalsIgnoreCase(enabled)) {
                return false;
            }
            if (enabled != null && !"true".equalsIgnoreCase(enabled)) {
                throw new IllegalArgumentException("Invalid SSL enabled flag");
            }
            return "true".equalsIgnoreCase(enabled)
                    || property("server.ssl.key-store") != null
                    || property("server.ssl.bundle") != null
                    || property("server.ssl.certificate") != null;
        }

        private String property(String key) {
            String value = environment.getProperty(key);
            if (value != null && value.length() > MAX_TEXT) {
                throw new UnsupportedObservation();
            }
            return value == null || value.isBlank() ? null : value.trim();
        }

        private List<String> activeProfiles() {
            Object raw = readField(environment, "activeProfiles");
            if (raw instanceof Set<?> profiles
                    && !profiles.isEmpty()
                    && profiles.size() <= MAX_ITEMS
                    && profiles.stream()
                            .allMatch(profile -> profile instanceof String text && text.length() <= MAX_TEXT)) {
                return profiles.stream().map(String.class::cast).toList();
            }
            String configured = property("spring.profiles.active");
            if (configured == null) {
                return List.of();
            }
            String[] profiles = configured.split(",");
            if (profiles.length > MAX_ITEMS) {
                throw new UnsupportedObservation();
            }
            return java.util.Arrays.stream(profiles).map(String::trim).toList();
        }

        private String hostProperty(String key) {
            return SecurityEnvironmentSnapshot.supportedText(
                    BootUiContributedProperties.firstHostProperty(environment, key));
        }

        private static boolean plainHttp(String value) {
            return value != null && value.trim().toLowerCase(Locale.ROOT).startsWith("http://");
        }

        private String securityLoggingLevel(List<String> messages) {
            if (environment instanceof ConfigurableEnvironment configurable) {
                int count = 0;
                int sources = 0;
                boolean incomplete = false;
                for (PropertySource<?> source : configurable.getPropertySources()) {
                    if (++sources > MAX_ITEMS) {
                        throw new UnsupportedObservation();
                    }
                    Map<?, ?> values = safeMap(source);
                    if (values == null) {
                        incomplete |= SecurityEnvironmentSnapshot.incompleteInventory(source, "logging.level.");
                        continue;
                    }
                    for (Object raw : values.keySet()) {
                        if (++count > MAX_PROPERTIES) {
                            messages.add("Security logger override inventory limit reached.");
                            throw new UnsupportedObservation();
                        }
                        if (raw instanceof String key
                                && (key.equals("logging.level.org.springframework.security")
                                        || key.startsWith("logging.level.org.springframework.security."))
                                && SAFE_KEY.matcher(key).matches()) {
                            try {
                                String configured = property(key);
                                if (verbose(configured)) {
                                    return configured;
                                }
                            } catch (UnsupportedObservation
                                    | SecurityActuatorObservation.ObservationLimitException ex) {
                                incomplete = true;
                            }
                        }
                    }
                }
                if (incomplete) {
                    throw new UnsupportedObservation();
                }
            }
            for (String key : List.of(
                    "logging.level.org.springframework.security",
                    "logging.level.org.springframework",
                    "logging.level.org",
                    "logging.level.root")) {
                String inherited = property(key);
                if (inherited != null) {
                    return inherited;
                }
            }
            return null;
        }

        private static boolean verbose(String level) {
            return "DEBUG".equalsIgnoreCase(level) || "TRACE".equalsIgnoreCase(level);
        }

        private Set<String> suspectedSecrets(List<String> messages) {
            if (!(environment instanceof ConfigurableEnvironment configurable)) {
                messages.add("Local secret provenance is unavailable.");
                return Set.of();
            }
            Set<String> result = new LinkedHashSet<>();
            Set<String> shadowed = new LinkedHashSet<>();
            boolean incomplete = false;
            int count = 0;
            int sources = 0;
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (++sources > MAX_ITEMS) {
                    messages.add("Property source inventory limit reached.");
                    return result;
                }
                if (SecurityEnvironmentSnapshot.generatedValues(source)) {
                    continue;
                }
                Map<?, ?> values = safeMap(source);
                if (values == null) {
                    Set<String> keys = SecurityEnvironmentSnapshot.incompleteKeys(source);
                    if (keys != null
                            && keys.stream()
                                    .noneMatch(key -> SECRET_KEY.matcher(key).matches())) {
                        continue;
                    }
                    if (!incomplete) {
                        messages.add(
                                "Unsupported property source provenance; literal-secret classification is incomplete.");
                        incomplete = true;
                    }
                    continue;
                }
                boolean local = knownLocalApplicationSource(source);
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (++count > MAX_PROPERTIES || result.size() >= MAX_ITEMS) {
                        messages.add("Local credential-key inventory limit reached.");
                        return result;
                    }
                    Object rawValue = entry.getValue();
                    if (rawValue instanceof OriginTrackedValue tracked
                            && rawValue.getClass()
                                    .getName()
                                    .startsWith("org.springframework.boot.origin.OriginTrackedValue")) {
                        rawValue = tracked.getValue();
                    }
                    if (!(entry.getKey() instanceof String key)
                            || !shadowed.add(key.toLowerCase(Locale.ROOT).replaceAll("[_.-]", ""))
                            || !local
                            || !SAFE_KEY.matcher(key).matches()
                            || key.toLowerCase(Locale.ROOT).startsWith("bootui.")
                            || !SECRET_KEY.matcher(key).matches()
                            || !(rawValue instanceof String text)
                            || text.isBlank()
                            || text.contains("${")
                            || text.length() > MAX_TEXT
                            || "true".equalsIgnoreCase(text)
                            || "false".equalsIgnoreCase(text)
                            || text.contains("://")) {
                        continue;
                    }
                    try {
                        if (!text.trim().equals(property(key))) {
                            continue;
                        }
                    } catch (UnsupportedObservation | SecurityActuatorObservation.ObservationLimitException ex) {
                        if (!incomplete) {
                            messages.add(
                                    "Unsupported property source provenance; literal-secret classification is incomplete.");
                            incomplete = true;
                        }
                        continue;
                    }
                    result.add(key);
                }
            }
            return result;
        }

        private static Map<?, ?> safeMap(PropertySource<?> source) {
            String name = source.getClass().getName();
            if (Set.of(
                                    "org.springframework.core.env.MapPropertySource",
                                    "org.springframework.core.env.PropertiesPropertySource",
                                    "org.springframework.core.env.SystemEnvironmentPropertySource",
                                    "org.springframework.boot.env.OriginTrackedMapPropertySource",
                                    "org.springframework.boot.env.DefaultPropertiesPropertySource",
                                    "org.springframework.mock.env.MockPropertySource")
                            .contains(name)
                    && source instanceof MapPropertySource map) {
                return map.getSource();
            }
            return null;
        }

        private static boolean knownLocalApplicationSource(PropertySource<?> source) {
            if (!exact(source, "org.springframework.boot.env.OriginTrackedMapPropertySource")) {
                return false;
            }
            String name = source.getName();
            return name.startsWith("Config resource 'class path resource [")
                    || name.startsWith("Config resource 'file [")
                    || name.startsWith("Config resource 'file:");
        }
    }
}
