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

final class ReactiveCatchAllOrderRule extends AbstractReactiveSecurityRule {

    ReactiveCatchAllOrderRule() {
        super(
                new ReactiveSecurityRuleDefinition(
                        "SEC-RXF-AUTHZ-004",
                        "Place unconditional reactive chains after scoped chains",
                        ReactiveSecurityCategory.AUTHORIZATION,
                        "INFO",
                        "Reports a structurally known unconditional chain before later chains. Spring Security selects only the first matching chain; this does not imply a permissive authorization policy.",
                        "Place intentionally scoped chains before the unconditional fallback and review their declared order.",
                        "https://docs.spring.io/spring-security/reference/reactive/authorization/authorize-http-requests.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (int i = 0; i + 1 < context.chains().size(); i++) {
            WebFilterChainObservation chain = context.chains().get(i);
            if (chain.matchesAnyRequest()) {
                details.add(chain.describe() + " is unconditional and precedes later chains in first-match order.");
            }
        }
        if (details.isEmpty()
                && context.chains().size() > 1
                && context.chains().stream().anyMatch(chain -> chain.unconditionalMatcher() == null)) {
            return skipped("Chain matcher structure or order is not fully known.");
        }
        return violation(details);
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
            if (chain.filtersObserved() && chain.hasObservedInteractiveLoginFilter() && !chain.hasCsrfWebFilter()) {
                details.add(chain.describe()
                        + " has an OAuth2/OIDC or formLogin() login filter but no CsrfWebFilter is installed.");
            }
        }
        if (details.isEmpty() && context.chains().stream().anyMatch(chain -> !chain.authenticationObserved())) {
            return skipped("Authentication converter metadata is unsupported.");
        }
        return filterViolation(context, details);
    }
}

final class ReactiveBasicCsrfRule extends AbstractReactiveSecurityRule {

    ReactiveBasicCsrfRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CSRF-002",
                "Review CSRF protection for reactive HTTP Basic chains",
                ReactiveSecurityCategory.CSRF,
                "MEDIUM",
                "Detects observed HTTP Basic chains without CsrfWebFilter. Browsers can automatically send Basic credentials even without a server session. Browser login findings are reported separately.",
                "Keep CSRF protection for browser-accessible Basic authentication, or establish that clients cannot automatically attach credentials. Bearer-only APIs are not flagged.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (chain.filtersObserved()
                    && chain.basicAuthentication()
                    && !chain.hasObservedInteractiveLoginFilter()
                    && !chain.hasCsrfWebFilter()) {
                details.add(
                        chain.describe()
                                + " configures HTTP Basic without CsrfWebFilter; browsers can attach Basic credentials automatically.");
            }
        }
        if (details.isEmpty() && context.chains().stream().anyMatch(chain -> !chain.authenticationObserved())) {
            return skipped("Authentication converter metadata is unsupported.");
        }
        return filterViolation(context, details);
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
                "LOW",
                "Detects an inspectable reactive CorsConfigurationSource that permits every origin through the exact \"*\" value in allowedOrigins or allowedOriginPatterns.",
                "Public noncredentialed resources may intentionally allow all origins. Otherwise enumerate trusted origins; literal wildcard origins with credentials are rejected by Spring.",
                "https://docs.spring.io/spring-framework/reference/web/webflux-cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (CorsConfigObservation config : context.corsConfigs()) {
            if ((config.hasWildcardOrigin() || config.hasWildcardOriginPattern())
                    && !(config.hasWildcardOriginPattern()
                            && !config.hasWildcardOrigin()
                            && Boolean.TRUE.equals(config.allowCredentials()))) {
                details.add("CORS config for pattern '" + config.pattern() + "' uses wildcard origins. "
                        + (Boolean.TRUE.equals(config.allowCredentials())
                                ? "Spring rejects literal allowedOrigins=* with credentials; this is not credentialed wildcard access."
                                : "Confirm these resources are intended for public noncredentialed sharing."));
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
            if (!config.hasWildcardOrigin()
                    && config.hasWildcardOriginPattern()
                    && Boolean.TRUE.equals(config.allowCredentials())) {
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
                "LOW",
                "Detects origin patterns with a broad host wildcard beyond the exact \"*\" covered by SEC-RXF-CORS-001/002. A wildcard scheme with an exact trusted host is not arbitrary-host trust.",
                "Replace broad patterns with the exact origins (or tightly-scoped subdomain wildcards such as https://*.example.com) the application trusts; broad patterns combined with credentials let untrusted sites make authenticated cross-site calls.",
                "https://docs.spring.io/spring-framework/reference/web/webflux-cors.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        boolean credentialed = false;
        for (CorsConfigObservation config : context.corsConfigs()) {
            if (config.hasWildcardOrigin() || config.hasWildcardOriginPattern()) {
                continue;
            }
            List<String> broad = config.broadOriginPatterns();
            if (broad.isEmpty()) {
                continue;
            }
            boolean allowsCredentials = Boolean.TRUE.equals(config.allowCredentials());
            credentialed = credentialed || allowsCredentials;
            String suffix = allowsCredentials ? " with allowCredentials=true" : "";
            details.add("CORS config for pattern '" + config.pattern() + "' uses " + broad.size()
                    + " broad host origin pattern(s)" + suffix + ".");
        }
        if (details.isEmpty()) {
            return corsViolation(context, details);
        }
        return violation(credentialed ? "HIGH" : "LOW", details);
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
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if ((context.environment().globalTlsConfigured() || chain.hasHttpsRedirectFilter())
                    && chain.headerWritersObserved()
                    && chain.hasHeaderWriterWebFilter()
                    && !chain.hasHstsWriter()) {
                details.add(
                        chain.describe()
                                + " configures TLS or a chain-local HTTPS redirect without Spring's HSTS writer; delivered proxy headers are not observed.");
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
                        "Detects chains without effective framing protection, accounting for enforcing CSP frame-ancestors overriding X-Frame-Options.",
                        "Keep frame-options protection via .headers(h -> h.frameOptions(Customizer.withDefaults())) or enforce an appropriate CSP frame-ancestors policy.",
                        "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html#webflux-headers-frame-options"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (chain.headerWritersObserved()
                    && chain.cspObserved()
                    && chain.hasHeaderWriterWebFilter()
                    && (!chain.hasFrameOptionsWriter() || chain.hasEnforcingFrameAncestorsDirective())
                    && !chain.hasEnforcingFrameAncestorsPolicy()) {
                details.add(
                        chain.describe()
                                + " applies security headers without effective framing protection; enforcing CSP frame-ancestors overrides X-Frame-Options.");
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
            } else if (chain.onlyReportOnlyCsp()) {
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
                "MEDIUM",
                "Detects chains with authentication or authorization filters but no HttpHeaderWriterWebFilter, meaning Spring Security's own header protections are absent. A reverse proxy or custom filter may still add equivalent headers.",
                "Do not call .headers(h -> h.disable()) unless the application sets equivalent headers via another mechanism.",
                "https://docs.spring.io/spring-security/reference/reactive/exploits/headers.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            boolean hasSecurityFilters = chain.hasAuthorizationWebFilter() || chain.hasAuthenticationFilter();
            if (chain.filtersObserved() && hasSecurityFilters && !chain.hasHeaderWriterWebFilter()) {
                details.add(
                        chain.describe()
                                + " installs authentication/authorization filters but no Spring HttpHeaderWriterWebFilter. Custom filters or proxies may add equivalent delivered headers.");
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
            if (chain.hasHstsWriter() && chain.hasWeakHsts()) {
                details.add(
                        chain.describe()
                                + " configures HSTS with a max-age of "
                                + chain.hstsMaxAgeSeconds()
                                + (chain.hstsMaxAgeSeconds() == 0
                                        ? " seconds, which removes the browser's HSTS policy."
                                        : " seconds, below Spring's one-year default; confirm an intentional rollout (RFC 6797 mandates no universal minimum)."));
            }
        }
        if (details.isEmpty()
                && context.chains().stream()
                        .anyMatch(chain -> chain.hasHstsWriter() && chain.hstsMaxAgeSeconds() == null)) {
            return skipped("HSTS max-age could not be observed.");
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
                "Reviews wildcard web selection with sensitive endpoint configuration permitted by effective host excludes and access caps. Configuration does not prove endpoint availability or anonymous access.",
                "Explicitly list only the endpoints you need, add excludes, and review management.endpoint.<id>.access for sensitive endpoints.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.exposing"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        String include = context.environment().managementExposureInclude();
        if ("*".equals(include) && !context.effectiveSensitiveActuatorExposure().isEmpty()) {
            return violation(List.of("Wildcard Actuator web selection permits sensitive endpoint configuration: "
                    + String.join(", ", context.effectiveSensitiveActuatorExposure())
                    + ". Actual endpoint availability and management authorization require separate review."));
        }
        return context.environment().actuatorObservationComplete()
                ? pass()
                : skipped("Effective Actuator configuration could not be fully observed.");
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
        if ("*".equals(context.environment().managementExposureInclude()) && !exposed.isEmpty()) {
            return pass(); // The same endpoint selection is already reported by ACT-001.
        }
        if (exposed.isEmpty()) {
            return context.environment().actuatorObservationComplete()
                    ? pass()
                    : skipped("Effective Actuator configuration could not be fully observed.");
        }
        return violation(List.of(
                "Sensitive Actuator endpoint configuration selected for web exposure: " + String.join(", ", exposed)
                        + ". Verify actual endpoint availability, authentication and restricted network access."));
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
            return context.environment().actuatorObservationComplete()
                    ? pass()
                    : skipped("Effective Actuator configuration could not be fully observed.");
        }
        if (context.environment().managementServerPortConfigured()) {
            return skipped("A separate management context's authorization is not observed by application chains.");
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
                        "Consider a separate management listener with explicit network binding/access restrictions. A different port alone is not a firewall.",
                        "https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-port"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (!context.exposesBeyondHealthAndInfo()) {
            return context.environment().actuatorObservationComplete()
                    ? pass()
                    : skipped("Effective Actuator configuration could not be fully observed.");
        }
        if (context.environment().managementServerPortConfigured()) {
            return pass();
        }
        return violation(
                List.of(
                        "Sensitive Actuator endpoints are exposed on the application's main port. "
                                + "Consider a separate listener and explicit network restrictions; a different port alone is not protection."));
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
                        "Use show-values=never unless callers should see values. Use when-authorized with appropriate roles for authorized disclosure, and verify endpoint authorization separately.",
                        "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        List<String> details = new ArrayList<>();
        if (context.environment().managementEnvShowValuesAlways()
                && context.environment().managementEnvWebExposed()) {
            details.add(
                    "Host management.endpoint.env.show-values=always permits unsanitized values for authorized endpoint callers; anonymous reachability is not established.");
        }
        if (context.environment().managementConfigPropsShowValuesAlways()
                && context.environment().managementConfigPropsWebExposed()) {
            details.add(
                    "Host management.endpoint.configprops.show-values=always permits unsanitized values for endpoint callers; anonymous reachability is not established.");
        }
        if (details.isEmpty() && !context.environment().actuatorObservationComplete()) {
            return skipped("Effective Actuator configuration could not be fully observed.");
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
                "INFO",
                "Detects the supported spring.security.oauth2.resourceserver.jwt.public-key-location configuration. Static verification keys are valid but require an explicit rotation process.",
                "Document an out-of-band rotation process for this supported static trust anchor; remote JWKS is optional. Custom decoder behavior is not established by these properties.",
                "https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html"));
    }

    @Override
    SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context) {
        if (context.environment().oauth2JwtStaticPublicKeyConfigured()) {
            return violation(
                    List.of(
                            "spring.security.oauth2.resourceserver.jwt.public-key-location declares a supported static verification key; review its out-of-band rotation process. Custom decoder settings are not inferred."));
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
                "Reviews production chains without observed direct server TLS or a known unconditional chain-local HTTPS redirect. Forwarded-header handling does not prove TLS enforcement; external ingress remains unobserved.",
                "Confirm upstream TLS enforcement explicitly or configure direct server TLS / chain-local HTTPS redirects. Forwarded headers alone do not establish enforcement.",
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
        if (!context.environment().globalTlsObserved()) {
            return skipped("Direct server TLS configuration could not be established.");
        }
        List<String> details = new ArrayList<>();
        for (WebFilterChainObservation chain : context.chains()) {
            if (chain.filtersObserved() && !chain.hasHttpsRedirectFilter()) {
                details.add(
                        chain.describe()
                                + " has no observed direct TLS or unconditional HTTPS redirect. Verify external ingress enforcement separately; forwarding is not proof of TLS.");
            }
        }
        return filterViolation(context, details);
    }
}

final class ReactiveHardcodedSecretPropertyRule extends AbstractReactiveSecurityRule {

    ReactiveHardcodedSecretPropertyRule() {
        super(new ReactiveSecurityRuleDefinition(
                "SEC-RXF-CONFIG-003",
                "Credentials or secrets should not be hardcoded in application properties",
                ReactiveSecurityCategory.CONFIGURATION,
                "HIGH",
                "Reviews credential-shaped keys with literal values in supported local application configuration sources. External providers, metadata settings and placeholders are not evidence of hardcoding. Only bounded property names are reported.",
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
                "Detects a chain with both Spring Security's bearer-token converter and an observed OAuth2 login or formLogin() filter. OAuth2 client grants alone are not login. This mixed topology may be intentional; filter presence does not prove SecurityContext persistence.",
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
        if (details.isEmpty() && context.chains().stream().anyMatch(chain -> !chain.authenticationObserved())) {
            return skipped("Authentication converter metadata is unsupported.");
        }
        return filterViolation(context, details);
    }
}
