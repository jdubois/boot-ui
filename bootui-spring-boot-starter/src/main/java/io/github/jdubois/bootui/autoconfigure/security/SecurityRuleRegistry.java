package io.github.jdubois.bootui.autoconfigure.security;

import java.util.List;
import java.util.Set;

final class SecurityRuleRegistry {

    /**
     * Rule IDs that were removed from the catalogue. They stay reserved so existing dismissals never silently apply
     * to a different check; see docs/SECURITY-CHECKS.md.
     */
    static final Set<String> RETIRED_RULE_IDS = Set.of(
            "SEC-AUTH-003",
            "SEC-AUTH-005",
            "SEC-SESSION-005",
            "SEC-SESSION-007",
            "SEC-SESSION-009",
            "SEC-HEAD-005",
            "SEC-HEAD-006",
            "SEC-HEAD-010",
            "SEC-CORS-003",
            "SEC-CORS-004",
            "SEC-ACT-004",
            "SEC-OAUTH-001",
            "SEC-CONFIG-003",
            "SEC-CONFIG-004",
            "SEC-CONFIG-005");

    private static final List<SecurityRule> ACTIVE_RULES = List.of(
            // Authentication & passwords
            new NoOpPasswordEncoderRule(),
            new WeakPasswordEncoderRule(),
            new WeakBcryptStrengthRule(),
            new DefaultInMemoryUserRule(),
            new BasicAuthWithoutTlsRule(),
            new FormLoginWithoutTlsRule(),
            new UsernameEnumerationRiskRule(),
            new GeneratedUserInProductionRule(),
            // Authorization
            new MissingAuthorizationFilterRule(),
            new PermitAllCatchAllRule(),
            new EffectivelyDisabledSecurityRule(),
            new CatchAllChainOrderingRule(),
            new AuthorizationRuleShadowedRule(),
            // CSRF
            new CsrfDisabledStatefulRule(),
            new CsrfGloballyDisabledRule(),
            // Session management
            new SessionFixationRule(),
            new SessionCookieSecureRule(),
            new SessionCookieHttpOnlyRule(),
            new SessionCookieSameSiteRule(),
            new BearerTokenStatefulRule(),
            new WeakRememberMeKeyRule(),
            // Transport & security headers
            new HstsHeaderRule(),
            new FrameOptionsRule(),
            new ContentSecurityPolicyRule(),
            new ContentTypeOptionsRule(),
            new HeaderWritersDisabledRule(),
            new WeakHstsPolicyRule(),
            new WeakContentSecurityPolicyRule(),
            // CORS
            new CorsWildcardOriginRule(),
            new CorsWildcardWithCredentialsRule(),
            new BroadCorsOriginPatternRule(),
            // Method security
            new MethodSecurityAnnotationsIgnoredRule(),
            new LegacyGlobalMethodSecurityRule(),
            // Actuator exposure
            new ActuatorWildcardExposureRule(),
            new ActuatorSensitiveExposureRule(),
            new ActuatorUnprotectedRule(),
            new ShutdownEndpointEnabledRule(),
            new ManagementPortIsolationRule(),
            new ActuatorShowValuesRule(),
            // OAuth2 / JWT resource server
            new JwtAudienceValidationRule(),
            new JwtStaticKeyRule(),
            new InsecureJwtMetadataUrlRule(),
            new InsecureIntrospectionUrlRule(),
            new InsecureOAuth2ClientEndpointRule(),
            // Configuration hygiene
            new SecurityDebugRule(),
            new H2ConsoleFrameOptionsRule(),
            new HttpsEnforcementRule(),
            new HardcodedSecretPropertyRule(),
            new StrictHttpFirewallWeakenedRule(),
            new SecurityDebugLoggingProductionRule());

    private SecurityRuleRegistry() {}

    static List<SecurityRule> activeRules() {
        return ACTIVE_RULES;
    }
}
