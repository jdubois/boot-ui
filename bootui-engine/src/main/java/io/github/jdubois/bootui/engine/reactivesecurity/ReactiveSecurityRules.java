package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// ---------------------------------------------------------------------------
// Authorization
// ---------------------------------------------------------------------------

final class ReactiveAuthorizationFilterRule extends AbstractReactiveSecurityRule {

    ReactiveAuthorizationFilterRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-AUTHZ-001",
                        "Every reactive filter chain should enforce authorization",
                        ReactiveSecurityCategory.AUTHORIZATION,
                        "HIGH",
                        "Detects a SecurityWebFilterChain whose observed Spring Security filters contain no AuthorizationWebFilter. Custom authorization filters remain outside this bounded snapshot.",
                        "Add authorizeExchange(...) with at least anyExchange().authenticated() (or denyAll), or verify equivalent custom authorization explicitly.",
                        "https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (Boolean.FALSE.equals(chain.authorizationFilterPresent())) {
                details.add(chain.describe() + " installs no observed AuthorizationWebFilter.");
            }
        }
        return filterViolation(context, details);
    }
}

final class ReactiveCatchAllWithoutAuthorizationRule extends AbstractReactiveSecurityRule {

    ReactiveCatchAllWithoutAuthorizationRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-AUTHZ-002",
                        "Catch-all reactive chains with authentication should install authorization",
                        ReactiveSecurityCategory.AUTHORIZATION,
                        "HIGH",
                        "Detects a catch-all reactive chain that installs authentication filters but no AuthorizationWebFilter. Runtime filter inspection cannot distinguish permitAll from authenticated authorization decisions once an AuthorizationWebFilter is present.",
                        "Add authorizeExchange(...) and finish with anyExchange().authenticated() or denyAll(); keep explicit permitAll matchers only for public endpoints.",
                        "https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (Boolean.FALSE.equals(chain.authorizationFilterPresent())
                    && chain.matchesAnyRequest()
                    && chain.hasAuthenticationFilter()) {
                details.add(
                        chain.describe()
                                + " matches every request and configures authentication but installs no observed AuthorizationWebFilter.");
            }
        }
        return filterViolation(context, details);
    }
}

final class ReactiveEffectivelyDisabledSecurityRule extends AbstractReactiveSecurityRule {

    ReactiveEffectivelyDisabledSecurityRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-AUTHZ-003",
                        "Reactive applications should not omit both authentication and authorization",
                        ReactiveSecurityCategory.AUTHORIZATION,
                        "HIGH",
                        "Detects when every observed reactive filter chain omits both AuthorizationWebFilter and authentication filters. Custom filters remain outside this bounded observation.",
                        "Define authentication and authorizeExchange rules for non-public endpoints, or verify that custom filters provide equivalent protection.",
                        "https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<WebFilterChainObservation> chains = context.chains();
        if (chains.isEmpty()) {
            return pass();
        }
        boolean anyUnknown = chains.stream().anyMatch(chain -> chain.authorizationFilterPresent() == null);
        if (anyUnknown) {
            return skipped("Web filters could not be observed for every reactive security chain.");
        }
        boolean allMissingAuthorization =
                chains.stream().allMatch(chain -> Boolean.FALSE.equals(chain.authorizationFilterPresent()));
        boolean anyAuthentication = chains.stream().anyMatch(WebFilterChainObservation::hasAuthenticationFilter);
        if (allMissingAuthorization && !anyAuthentication) {
            return violation(
                    List.of(
                            "All " + chains.size()
                                    + " observed reactive security filter chains omit both AuthorizationWebFilter and authentication filters."));
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// CSRF
// ---------------------------------------------------------------------------

final class ReactiveCsrfDisabledLoginRule extends AbstractReactiveSecurityRule {

    ReactiveCsrfDisabledLoginRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CSRF-001",
                "Reactive OAuth2/OIDC or formLogin() chains should enable CSRF protection",
                ReactiveSecurityCategory.CSRF,
                "HIGH",
                "Detects a reactive chain with an observed OAuth2/OIDC login filter or a formLogin() authentication converter but no CsrfWebFilter. Without CSRF protection, cross-origin state-changing requests can be forged.",
                "Keep CSRF enabled for browser login chains, using .csrf(Customizer.withDefaults()) or a CookieServerCsrfTokenRepository as appropriate.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (chain.hasObservedInteractiveLoginFilter() && !chain.hasCsrfWebFilter()) {
                details.add(chain.describe()
                        + " has an OAuth2/OIDC or formLogin() login filter but no CsrfWebFilter is installed.");
            }
        }
        return filterViolation(context, details);
    }
}

final class ReactiveCsrfGloballyDisabledRule extends AbstractReactiveSecurityRule {

    ReactiveCsrfGloballyDisabledRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CSRF-002",
                "CSRF should not be globally disabled in reactive applications",
                ReactiveSecurityCategory.CSRF,
                "MEDIUM",
                "Detects when none of the registered SecurityWebFilterChain beans installs a CsrfWebFilter. Stateless REST APIs using only bearer tokens do not need CSRF; check whether all chains are intentionally stateless.",
                "For non-stateless applications add .csrf(Customizer.withDefaults()) to chains using session-based or cookie-based authentication.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (context.chains().isEmpty()) {
            return pass();
        }
        boolean anyHasCsrf = context.chains().stream().anyMatch(WebFilterChainObservation::hasCsrfWebFilter);
        if (anyHasCsrf) {
            return pass();
        }
        if (context.chains().stream().anyMatch(chain -> !chain.filtersObserved())) {
            return skipped("Web filters could not be observed for every reactive security chain.");
        }
        return violation(List.of("No CsrfWebFilter was found across all "
                + context.chains().size()
                + " registered reactive security filter chains. Verify all chains are intentionally stateless."));
    }
}

// ---------------------------------------------------------------------------
// CORS
// ---------------------------------------------------------------------------

final class ReactiveCorsWildcardOriginRule extends AbstractReactiveSecurityRule {

    ReactiveCorsWildcardOriginRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CORS-001",
                "CORS should not allow wildcard origins in reactive applications",
                ReactiveSecurityCategory.CORS,
                "MEDIUM",
                "Detects an inspectable reactive CorsConfigurationSource that permits every origin through the exact \"*\" value in allowedOrigins or allowedOriginPatterns.",
                "Enumerate allowed origins explicitly, e.g. https://app.example.com, instead of using the wildcard.",
                "https://docs.spring.io/spring-framework/reference/web/webflux-cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigObservation config : context.corsConfigs()) {
            if (config.hasWildcardOrigin() || config.hasWildcardOriginPattern()) {
                details.add("CORS config for pattern '" + config.pattern() + "' allows all origins (wildcard).");
            }
        }
        return corsViolation(context, details);
    }
}

final class ReactiveCorsWildcardWithCredentialsRule extends AbstractReactiveSecurityRule {

    ReactiveCorsWildcardWithCredentialsRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CORS-002",
                "Credentialed reactive CORS must not trust every origin pattern",
                ReactiveSecurityCategory.CORS,
                "HIGH",
                "Detects allowedOriginPatterns=\"*\" with allowCredentials=true, a legal Spring configuration that reflects arbitrary origins while allowing credentials. Spring rejects the separate allowedOrigins=\"*\" plus credentials combination.",
                "Replace the wildcard origin pattern with explicit trusted origins before enabling credentials.",
                "https://docs.spring.io/spring-framework/reference/web/webflux-cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigObservation config : context.corsConfigs()) {
            if (config.hasWildcardOriginPattern() && Boolean.TRUE.equals(config.allowCredentials())) {
                details.add("CORS config for pattern '"
                        + config.pattern()
                        + "' combines allowedOriginPatterns=\"*\" with allowCredentials=true.");
            }
        }
        return corsViolation(context, details);
    }
}

final class ReactiveBroadCorsOriginPatternRule extends AbstractReactiveSecurityRule {

    ReactiveBroadCorsOriginPatternRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CORS-003",
                "Reactive CORS should not allow broad origin patterns",
                ReactiveSecurityCategory.CORS,
                "MEDIUM",
                "Detects allowedOriginPatterns that match a dangerously broad set of origins (wildcard scheme or host, e.g. https://*, *://*, *.com) beyond the exact \"*\" already covered by SEC-RXF-CORS-001/002.",
                "Replace broad patterns with the exact origins (or tightly-scoped subdomain wildcards such as https://*.example.com) the application trusts; broad patterns combined with credentials let untrusted sites make authenticated cross-site calls.",
                "https://docs.spring.io/spring-framework/reference/web/webflux-cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        boolean credentialed = false;
        for (CorsConfigObservation config : context.corsConfigs()) {
            List<String> broad = config.broadOriginPatterns();
            if (broad.isEmpty()) {
                continue;
            }
            boolean allowsCredentials = Boolean.TRUE.equals(config.allowCredentials());
            credentialed = credentialed || allowsCredentials;
            String suffix = allowsCredentials ? " with allowCredentials=true" : "";
            details.add("CORS config for pattern '" + config.pattern() + "' uses broad origin patterns " + broad
                    + suffix + ".");
        }
        if (details.isEmpty()) {
            return corsViolation(context, details);
        }
        return violation(credentialed ? "HIGH" : "MEDIUM", details);
    }
}

// ---------------------------------------------------------------------------
// Transport & security headers
// ---------------------------------------------------------------------------

final class ReactiveHstsHeaderRule extends AbstractReactiveSecurityRule {

    ReactiveHstsHeaderRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-HEAD-001",
                        "HSTS header should be configured for reactive applications over TLS",
                        ReactiveSecurityCategory.HEADERS,
                        "MEDIUM",
                        "Detects chains that apply security headers (HttpHeaderWriterWebFilter) but do not include an HSTS writer while TLS is configured.",
                        "Keep Spring Security's StrictTransportSecurityServerHttpHeadersWriter defaults via .headers(h -> h.hsts(Customizer.withDefaults())).",
                        "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-hsts"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.isTlsConfigured()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (chain.headerWritersObserved() && chain.hasHeaderWriterWebFilter() && !chain.hasHstsWriter()) {
                details.add(chain.describe() + " applies security headers without HSTS while TLS is configured.");
            }
        }
        return headerViolation(context, details);
    }
}

final class ReactiveFrameOptionsRule extends AbstractReactiveSecurityRule {

    ReactiveFrameOptionsRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-HEAD-002",
                        "X-Frame-Options header should be set in reactive chains",
                        ReactiveSecurityCategory.HEADERS,
                        "MEDIUM",
                        "Detects chains with security header writers but neither a FrameOptions writer nor an enforcing CSP frame-ancestors directive.",
                        "Keep frame-options protection via .headers(h -> h.frameOptions(Customizer.withDefaults())) or enforce an appropriate CSP frame-ancestors policy.",
                        "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-frame-options"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (chain.headerWritersObserved()
                    && chain.hasHeaderWriterWebFilter()
                    && !chain.hasFrameOptionsWriter()
                    && !chain.hasEnforcingFrameAncestorsPolicy()) {
                details.add(
                        chain.describe()
                                + " applies security headers without X-Frame-Options or an enforcing CSP frame-ancestors directive.");
            }
        }
        return headerViolation(context, details);
    }
}

final class ReactiveContentTypeOptionsRule extends AbstractReactiveSecurityRule {

    ReactiveContentTypeOptionsRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-HEAD-003",
                        "X-Content-Type-Options should be set in reactive chains",
                        ReactiveSecurityCategory.HEADERS,
                        "LOW",
                        "Detects chains with security header writers but no ContentTypeOptions writer.",
                        "Enable content-type sniffing prevention via .headers(h -> h.contentTypeOptions(Customizer.withDefaults())).",
                        "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-content-type-options"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (chain.headerWritersObserved()
                    && chain.hasHeaderWriterWebFilter()
                    && !chain.hasContentTypeOptionsWriter()) {
                details.add(chain.describe() + " applies security headers without X-Content-Type-Options (nosniff).");
            }
        }
        return headerViolation(context, details);
    }
}

final class ReactiveContentSecurityPolicyRule extends AbstractReactiveSecurityRule {

    ReactiveContentSecurityPolicyRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-HEAD-004",
                "Review Content-Security-Policy enforcement for reactive browser chains",
                ReactiveSecurityCategory.HEADERS,
                "LOW",
                "Reports chains whose Spring Security header writers omit Content-Security-Policy or configure it as report-only. Spring intentionally provides no default because a safe policy depends on application context.",
                "For browser-facing responses, configure a tailored enforcing policy via .headers(h -> h.contentSecurityPolicy(...)); use report-only mode only during a bounded rollout.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-csp"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (!chain.headerWritersObserved()) {
                continue;
            }
            if (chain.hasHeaderWriterWebFilter() && !chain.hasCspWriter()) {
                details.add(
                        chain.describe()
                                + " applies Spring Security headers without a Content-Security-Policy; review whether this chain serves browser content.");
            } else if (chain.hasCspWriter() && Boolean.TRUE.equals(chain.cspReportOnly())) {
                details.add(
                        chain.describe()
                                + " configures Content-Security-Policy-Report-Only, which monitors policy violations but does not enforce the policy.");
            }
        }
        return headerViolation(context, details);
    }
}

final class ReactiveHeadersDisabledRule extends AbstractReactiveSecurityRule {

    ReactiveHeadersDisabledRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-HEAD-005",
                "Security headers should not be disabled in reactive chains",
                ReactiveSecurityCategory.HEADERS,
                "HIGH",
                "Detects chains with authentication or authorization filters but no HttpHeaderWriterWebFilter, meaning Spring Security's own header protections are absent. A reverse proxy or custom filter may still add equivalent headers.",
                "Do not call .headers(h -> h.disable()) unless the application sets equivalent headers via another mechanism.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            boolean hasSecurityFilters = chain.hasAuthorizationWebFilter() || chain.hasAuthenticationFilter();
            if (hasSecurityFilters && !chain.hasHeaderWriterWebFilter()) {
                details.add(
                        chain.describe()
                                + " enforces authentication/authorization but installs no security header writer (HttpHeaderWriterWebFilter).");
            }
        }
        return filterViolation(context, details);
    }
}

final class ReactiveWeakHstsPolicyRule extends AbstractReactiveSecurityRule {

    ReactiveWeakHstsPolicyRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-HEAD-006",
                        "Review HSTS max-age values below Spring Security's one-year default",
                        ReactiveSecurityCategory.HEADERS,
                        "LOW",
                        "Detects an HSTS writer configured below Spring Security's one-year default (31,536,000 seconds). RFC 6797 does not mandate a universal minimum.",
                        "Use a shorter rollout only intentionally; otherwise keep Spring Security's Duration.ofDays(365) default and evaluate includeSubDomains separately.",
                        "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-hsts"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (chain.headerWritersObserved() && chain.hasHstsWriter() && chain.hasWeakHsts()) {
                details.add(chain.describe()
                        + " configures HSTS with a max-age of "
                        + chain.hstsMaxAgeSeconds()
                        + " seconds, below the recommended 31,536,000 (one year).");
            }
        }
        return headerViolation(context, details);
    }
}

// ---------------------------------------------------------------------------
// Actuator exposure
// ---------------------------------------------------------------------------

final class ReactiveActuatorWildcardExposureRule extends AbstractReactiveSecurityRule {

    ReactiveActuatorWildcardExposureRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-ACT-001",
                "Actuator endpoints should not be exposed with a wildcard",
                ReactiveSecurityCategory.ACTUATOR,
                "HIGH",
                "Detects management.endpoints.web.exposure.include=* without any exclude. Endpoint enablement and Boot 4 access settings still determine which exposed endpoints are callable.",
                "Explicitly list only the endpoints you need, add excludes, and review management.endpoint.<id>.access for sensitive endpoints.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        String include = context.environment().managementExposureInclude();
        String exclude = context.environment().managementExposureExclude();
        if ("*".equals(include) && (exclude == null || exclude.isBlank())) {
            return violation(
                    List.of(
                            "management.endpoints.web.exposure.include=* exposes all Actuator endpoints, including sensitive ones (env, beans, heapdump, shutdown)."));
        }
        return pass();
    }
}

final class ReactiveActuatorSensitiveExposureRule extends AbstractReactiveSecurityRule {

    ReactiveActuatorSensitiveExposureRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-ACT-002",
                "Sensitive Actuator endpoints should be explicitly reviewed",
                ReactiveSecurityCategory.ACTUATOR,
                "MEDIUM",
                "Detects one or more sensitive Actuator endpoints (env, beans, configprops, heapdump, threaddump, shutdown, loggers, mappings) explicitly included in management.endpoints.web.exposure.include.",
                "Restrict sensitive Actuator endpoints to a separate management port, or protect them with authentication/network policies.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        Set<String> exposed = context.effectiveSensitiveActuatorExposure();
        if (exposed.isEmpty()) {
            return pass();
        }
        return violation(List.of("Sensitive Actuator endpoints exposed: " + String.join(", ", exposed)
                + ". Ensure these are protected by authentication or a restricted network path."));
    }
}

final class ReactiveActuatorAuthorizationReviewRule extends AbstractReactiveSecurityRule {

    ReactiveActuatorAuthorizationReviewRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-ACT-003",
                "Review authorization for broad reactive Actuator exposure",
                ReactiveSecurityCategory.ACTUATOR,
                "MEDIUM",
                "Detects broad Actuator web exposure while every observed application chain omits AuthorizationWebFilter. The advisor cannot prove which chain matches a custom management path or separate management context.",
                "Verify the actual management path/port has explicit authorization or a restricted network path; add an Actuator-specific SecurityWebFilterChain when needed.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.security"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.exposesBeyondHealthAndInfo()) {
            return pass();
        }
        if (context.chains().stream().anyMatch(chain -> !chain.filtersObserved())) {
            return skipped("Web filters could not be observed for every reactive security chain.");
        }
        boolean allChainsObservedWithoutAuthorization = !context.chains().isEmpty()
                && context.chains().stream()
                        .allMatch(chain -> Boolean.FALSE.equals(chain.authorizationFilterPresent()));
        if (allChainsObservedWithoutAuthorization) {
            return violation(
                    List.of(
                            "Actuator endpoints beyond health/info are configured for web exposure, and no observed application chain installs AuthorizationWebFilter. Verify management-path authorization separately."));
        }
        return pass();
    }
}

final class ReactiveManagementPortIsolationRule extends AbstractReactiveSecurityRule {

    ReactiveManagementPortIsolationRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-ACT-004",
                        "Consider isolating Actuator endpoints on a separate management port",
                        ReactiveSecurityCategory.ACTUATOR,
                        "INFO",
                        "Detects that sensitive Actuator endpoints are exposed on the same port as the application, without a separate management port configured.",
                        "Set management.server.port to a non-public port so Actuator endpoints are not reachable via the application's main port.",
                        "https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.exposesBeyondHealthAndInfo()) {
            return pass();
        }
        if (context.environment().managementServerPortConfigured()) {
            return pass();
        }
        return violation(List.of("Sensitive Actuator endpoints are exposed on the application's main port. "
                + "Consider setting management.server.port to isolate them."));
    }
}

final class ReactiveActuatorShowValuesRule extends AbstractReactiveSecurityRule {

    ReactiveActuatorShowValuesRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-ACT-005",
                        "Reactive Actuator env/configprops values must stay sanitized",
                        ReactiveSecurityCategory.ACTUATOR,
                        "HIGH",
                        "Detects a web-exposed env or configprops endpoint whose host configuration sets show-values=always, revealing unsanitized property values to callers.",
                        "Leave show-values at never or when-authorized so the Actuator sanitizer masks sensitive values; only relax it behind strict authorization.",
                        "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        if (context.environment().managementEnvShowValuesAlways()
                && context.environment().managementEnvWebExposed()) {
            details.add("management.endpoint.env.show-values=always exposes unsanitized /env values.");
        }
        if (context.environment().managementConfigPropsShowValuesAlways()
                && context.environment().managementConfigPropsWebExposed()) {
            details.add("management.endpoint.configprops.show-values=always exposes unsanitized /configprops values.");
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// OAuth2 / JWT
// ---------------------------------------------------------------------------

final class ReactiveJwtStaticKeyRule extends AbstractReactiveSecurityRule {

    ReactiveJwtStaticKeyRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-OAUTH2-002",
                "Review rotation for reactive JWT static public keys",
                ReactiveSecurityCategory.OAUTH2,
                "LOW",
                "Detects the supported spring.security.oauth2.resourceserver.jwt.public-key-location configuration. Static verification keys are valid but require an explicit rotation process.",
                "Document manual key rotation or prefer issuer-uri/jwk-set-uri when the authorization server publishes a trusted JWKS endpoint.",
                "https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (context.environment().oauth2JwtStaticPublicKeyConfigured()) {
            return violation(
                    List.of(
                            "spring.security.oauth2.resourceserver.jwt.public-key-location configures a static verification key; prefer issuer-uri or jwk-set-uri for key rotation."));
        }
        return pass();
    }
}

final class ReactiveInsecureJwtMetadataUrlRule extends AbstractReactiveSecurityRule {

    ReactiveInsecureJwtMetadataUrlRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-OAUTH2-003",
                "JWT issuer URI and JWKS URI should use HTTPS in reactive applications",
                ReactiveSecurityCategory.OAUTH2,
                "HIGH",
                "Detects spring.security.oauth2.resourceserver.jwt.issuer-uri or jwk-set-uri configured with a plain HTTP URL in a production profile.",
                "Use https:// for all issuer-uri and jwk-set-uri values in non-development environments.",
                "https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        if (context.environment().oauth2JwtIssuerUsesPlainHttp()) {
            details.add("spring.security.oauth2.resourceserver.jwt.issuer-uri uses plain HTTP; switch to HTTPS.");
        }
        if (context.environment().oauth2JwtJwkSetUsesPlainHttp()) {
            details.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri uses plain HTTP; switch to HTTPS.");
        }
        return violation(details);
    }
}

final class ReactiveInsecureOpaqueTokenIntrospectionUrlRule extends AbstractReactiveSecurityRule {

    ReactiveInsecureOpaqueTokenIntrospectionUrlRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-OAUTH2-004",
                "Opaque-token introspection must use HTTPS in reactive production applications",
                ReactiveSecurityCategory.OAUTH2,
                "HIGH",
                "Detects spring.security.oauth2.resourceserver.opaquetoken.introspection-uri configured with plain HTTP in a production profile. RFC 7662 requires TLS for introspection because access tokens and authorization metadata cross this channel.",
                "Use an https:// introspection URI and validate the authorization server certificate.",
                "https://www.rfc-editor.org/rfc/rfc7662.html#section-4"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.isProductionProfileActive()
                || !context.environment().oauth2OpaqueTokenIntrospectionUsesPlainHttp()) {
            return pass();
        }
        return violation(
                List.of(
                        "spring.security.oauth2.resourceserver.opaquetoken.introspection-uri uses plain HTTP; RFC 7662 requires TLS."));
    }
}

// ---------------------------------------------------------------------------
// Configuration hygiene
// ---------------------------------------------------------------------------

final class ReactiveHttpsEnforcementRule extends AbstractReactiveSecurityRule {

    ReactiveHttpsEnforcementRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CONFIG-002",
                "Review HTTPS enforcement for reactive production applications",
                ReactiveSecurityCategory.CONFIGURATION,
                "MEDIUM",
                "Detects a production profile where BootUI cannot observe server TLS, trusted forwarded-header handling, or HttpsRedirectWebFilter. External proxy policy remains outside runtime configuration inspection.",
                "Confirm upstream TLS explicitly, configure server.forward-headers-strategy when trusted, or add .redirectToHttps(Customizer.withDefaults()) to production chains.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/https.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        if (context.isTlsConfigured()) {
            return pass();
        }
        return violation(
                List.of(
                        "A production profile is active, but BootUI could not confirm TLS, trusted forwarded-header handling, or HttpsRedirectWebFilter. Verify the deployment boundary."));
    }
}

final class ReactiveHardcodedSecretPropertyRule extends AbstractReactiveSecurityRule {

    ReactiveHardcodedSecretPropertyRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CONFIG-003",
                "Credentials or secrets should not be hardcoded in application properties",
                ReactiveSecurityCategory.CONFIGURATION,
                "HIGH",
                "Detects application property keys whose names suggest they hold credentials or secrets and whose values appear to be literal strings rather than placeholder references. Only the property name is reported; the value itself is never surfaced.",
                "Move secrets to environment variables, a secrets manager, Spring Cloud Vault, or another externalization mechanism.",
                "https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        Set<String> suspected = context.suspectedHardcodedSecretKeys();
        if (suspected.isEmpty()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (String key : suspected) {
            details.add("Property key '" + key + "' appears to hold a literal credential (value not shown).");
        }
        return violation(details);
    }
}

final class ReactiveSecurityDebugLoggingProductionRule extends AbstractReactiveSecurityRule {

    ReactiveSecurityDebugLoggingProductionRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CONFIG-004",
                "Spring Security DEBUG or TRACE logging should not run in production",
                ReactiveSecurityCategory.CONFIGURATION,
                "MEDIUM",
                "Detects DEBUG- or TRACE-level logging configured for Spring Security packages while a production profile is active.",
                "Set logging.level.org.springframework.security to INFO or WARN in the production profile.",
                "https://docs.spring.io/spring-security/reference/reactive/index.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        String level = context.environment().securityLoggingLevel();
        if (level != null && ("DEBUG".equalsIgnoreCase(level.trim()) || "TRACE".equalsIgnoreCase(level.trim()))) {
            return violation(List.of("Spring Security logging is set to "
                    + level.trim().toUpperCase(java.util.Locale.ROOT)
                    + " while a production profile is active."));
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Session management
// ---------------------------------------------------------------------------

final class ReactiveMixedBearerAndLoginRule extends AbstractReactiveSecurityRule {

    ReactiveMixedBearerAndLoginRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-SESSION-001",
                "Review reactive chains that mix bearer-token and browser login filters",
                ReactiveSecurityCategory.SESSION,
                "LOW",
                "Detects a chain with both Spring Security's bearer-token converter and an observed OAuth2/OIDC login, authorization-code client, or formLogin() filter. This mixed topology may be intentional; filter presence does not prove SecurityContext persistence.",
                "Prefer separate ordered SecurityWebFilterChain beans for browser login and resource-server paths. For a pure bearer chain, use securityContextRepository(NoOpServerSecurityContextRepository.getInstance()); WebFlux has no SessionCreationPolicy API.",
                "https://docs.spring.io/spring-security/reference/reactive/authentication/index.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (chain.bearerTokenAuthentication() && chain.hasObservedInteractiveLoginFilter()) {
                details.add(
                        chain.describe()
                                + " configures both bearer-token authentication and an OAuth2/OIDC or formLogin() browser filter; review whether separate chains would express the two security models more safely.");
            }
        }
        return filterViolation(context, details);
    }
}
