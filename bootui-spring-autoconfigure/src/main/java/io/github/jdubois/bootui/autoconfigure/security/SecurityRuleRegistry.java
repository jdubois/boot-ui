package io.github.jdubois.bootui.autoconfigure.security;

import java.util.List;

final class SecurityRuleRegistry {

    private static final List<SecurityRule> ACTIVE_RULES = List.of(
            // Authentication & passwords
            new NoOpPasswordEncoderRule(),
            new WeakPasswordEncoderRule(),
            new MissingPasswordEncoderRule(),
            new WeakBcryptStrengthRule(),
            new DefaultInMemoryUserRule(),
            new DefaultLoginPageProductionRule(),
            new BasicAuthWithoutTlsRule(),
            // Authorization
            new MissingAuthorizationFilterRule(),
            new PermitAllCatchAllRule(),
            new EffectivelyDisabledSecurityRule(),
            new CatchAllChainOrderingRule(),
            // CSRF
            new CsrfDisabledStatefulRule(),
            new CsrfGloballyDisabledRule(),
            // Session management
            new SessionFixationRule(),
            new SessionCookieSecureRule(),
            new SessionCookieHttpOnlyRule(),
            new SessionCookieSameSiteRule(),
            new SessionTimeoutRule(),
            new BearerTokenStatefulRule(),
            new ConcurrentSessionControlRule(),
            // Transport & security headers
            new HstsHeaderRule(),
            new FrameOptionsRule(),
            new ContentSecurityPolicyRule(),
            new ContentTypeOptionsRule(),
            new ReferrerPolicyHeaderRule(),
            new PermissionsPolicyHeaderRule(),
            new HeaderWritersDisabledRule(),
            new WeakHstsPolicyRule(),
            new WeakContentSecurityPolicyRule(),
            // CORS
            new CorsWildcardOriginRule(),
            new CorsWildcardWithCredentialsRule(),
            new CorsNotInSecurityChainRule(),
            new CorsWildcardMethodsHeadersRule(),
            new BroadCorsOriginPatternRule(),
            // Method security
            new MethodSecurityAnnotationsIgnoredRule(),
            new LegacyGlobalMethodSecurityRule(),
            // Actuator exposure
            new ActuatorWildcardExposureRule(),
            new ActuatorSensitiveExposureRule(),
            new ActuatorUnprotectedRule(),
            new HealthDetailsExposureRule(),
            new ShutdownEndpointEnabledRule(),
            new ManagementPortIsolationRule(),
            new ActuatorShowValuesRule(),
            // OAuth2 / JWT resource server
            new ResourceServerValidationRule(),
            new JwtAudienceValidationRule(),
            new JwtStaticKeyRule(),
            // Configuration hygiene
            new SecurityDebugRule(),
            new H2ConsoleFrameOptionsRule(),
            new WebSecurityConfigurerAdapterRule(),
            new ErrorResponseDisclosureRule(),
            new HttpsEnforcementRule(),
            new HardcodedSecretPropertyRule());

    private SecurityRuleRegistry() {}

    static List<SecurityRule> activeRules() {
        return ACTIVE_RULES;
    }
}
