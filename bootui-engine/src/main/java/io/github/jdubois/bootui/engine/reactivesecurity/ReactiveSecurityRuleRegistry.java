package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.List;

final class ReactiveSecurityRuleRegistry {

    static final int RULE_COUNT = 25;

    private static final List<ReactiveSecurityRule> ACTIVE_RULES = List.of(
            // Authorization
            new ReactiveAuthorizationFilterRule(),
            new ReactivePermitAllCatchAllRule(),
            new ReactiveEffectivelyDisabledSecurityRule(),
            // CSRF
            new ReactiveCsrfDisabledStatefulRule(),
            new ReactiveCsrfGloballyDisabledRule(),
            // CORS
            new ReactiveCorsWildcardOriginRule(),
            new ReactiveCorsWildcardWithCredentialsRule(),
            // Transport & security headers
            new ReactiveHstsHeaderRule(),
            new ReactiveFrameOptionsRule(),
            new ReactiveContentTypeOptionsRule(),
            new ReactiveContentSecurityPolicyRule(),
            new ReactiveHeadersDisabledRule(),
            new ReactiveWeakHstsPolicyRule(),
            // Actuator exposure
            new ReactiveActuatorWildcardExposureRule(),
            new ReactiveActuatorSensitiveExposureRule(),
            new ReactiveActuatorUnprotectedRule(),
            new ReactiveManagementPortIsolationRule(),
            // OAuth2 / JWT
            new ReactiveJwtAudienceValidationRule(),
            new ReactiveJwtStaticKeyRule(),
            new ReactiveInsecureJwtMetadataUrlRule(),
            // Configuration hygiene
            new ReactiveSecurityDebugRule(),
            new ReactiveHttpsEnforcementRule(),
            new ReactiveHardcodedSecretPropertyRule(),
            new ReactiveSecurityDebugLoggingProductionRule(),
            // Session management
            new ReactiveBearerTokenStatefulRule());

    private ReactiveSecurityRuleRegistry() {}

    static List<ReactiveSecurityRule> activeRules() {
        return ACTIVE_RULES;
    }
}
