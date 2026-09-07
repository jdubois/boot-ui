package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.List;

final class ReactiveSecurityRuleRegistry {

    static final int RULE_COUNT = 25;

    private static final List<ReactiveSecurityRule> ACTIVE_RULES = List.of(
            // Authorization
            new ReactiveAuthorizationFilterRule(),
            // AUTHZ-002 and AUTHZ-003 remain reserved: duplicate missing-filter evidence.
            new ReactiveCatchAllOrderRule(),
            // CSRF
            new ReactiveCsrfDisabledLoginRule(),
            new ReactiveBasicCsrfRule(),
            // CORS
            new ReactiveCorsWildcardOriginRule(),
            new ReactiveCorsWildcardWithCredentialsRule(),
            new ReactiveBroadCorsOriginPatternRule(),
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
            new ReactiveActuatorAuthorizationReviewRule(),
            new ReactiveManagementPortIsolationRule(),
            new ReactiveActuatorShowValuesRule(),
            // OAuth2 / JWT
            new ReactiveJwtStaticKeyRule(),
            new ReactiveInsecureJwtMetadataUrlRule(),
            new ReactiveInsecureOpaqueTokenIntrospectionUrlRule(),
            // Configuration hygiene
            new ReactiveHttpsEnforcementRule(),
            new ReactiveHardcodedSecretPropertyRule(),
            new ReactiveSecurityDebugLoggingProductionRule(),
            // Session management
            new ReactiveMixedBearerAndLoginRule());

    private ReactiveSecurityRuleRegistry() {}

    static List<ReactiveSecurityRule> activeRules() {
        return ACTIVE_RULES;
    }
}
