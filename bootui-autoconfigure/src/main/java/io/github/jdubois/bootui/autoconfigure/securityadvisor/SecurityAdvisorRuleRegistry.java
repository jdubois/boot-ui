package io.github.jdubois.bootui.autoconfigure.securityadvisor;

import java.util.List;

final class SecurityAdvisorRuleRegistry {

    private static final List<SecurityAdvisorRule> ACTIVE_RULES = List.of(
            // Authentication & passwords
            new NoOpPasswordEncoderRule(),
            new WeakPasswordEncoderRule(),
            new MissingPasswordEncoderRule(),
            new DefaultInMemoryUserRule(),
            new RememberMeKeyRule(),
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
            // Transport & security headers
            new HstsHeaderRule(),
            new FrameOptionsRule(),
            new ContentSecurityPolicyRule(),
            new ContentTypeOptionsRule(),
            new HttpBasicChannelSecurityRule(),
            // CORS
            new CorsWildcardOriginRule(),
            new CorsWildcardWithCredentialsRule(),
            new CorsNotInSecurityChainRule(),
            // Method security
            new MethodSecurityAnnotationsIgnoredRule(),
            new NoMethodSecurityRule(),
            new LegacyGlobalMethodSecurityRule(),
            // Actuator exposure
            new ActuatorWildcardExposureRule(),
            new ActuatorSensitiveExposureRule(),
            new ActuatorUnprotectedRule(),
            // OAuth2 / JWT resource server
            new ResourceServerValidationRule(),
            new JwtAudienceValidationRule(),
            new JwtStaticKeyRule(),
            // Configuration hygiene
            new SecurityDebugRule(),
            new H2ConsoleFrameOptionsRule(),
            new WebSecurityConfigurerAdapterRule(),
            new WebIgnoringRule());

    private SecurityAdvisorRuleRegistry() {}

    static List<SecurityAdvisorRule> activeRules() {
        return ACTIVE_RULES;
    }
}
