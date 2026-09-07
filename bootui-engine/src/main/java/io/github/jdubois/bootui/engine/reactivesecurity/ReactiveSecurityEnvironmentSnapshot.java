package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral, precomputed snapshot of the host-application {@code Environment} facts the
 * reactive Spring Security advisor rules need. Collected once per scan by the Spring adapter (which
 * owns all property-source reading, including skipping BootUI's own Actuator defaults) and handed to
 * the engine as plain values — the engine never touches a Spring {@code Environment} or
 * {@code PropertySource}.
 *
 * <p>{@link #suspectedHardcodedSecretKeys()} carries property <em>keys</em> only; the adapter never
 * includes the matched property values here, so the advisor cannot surface a secret's value even by
 * accident.</p>
 *
 * @param globalTlsConfigured whether direct server TLS is configured; explicit disable wins over
 *     remaining key material, and forwarded-header handling is not TLS enforcement
 * @param managementExposureInclude the effective, BootUI-default-skipping value of
 *     {@code management.endpoints.web.exposure.include}, or {@code null} if not configured
 * @param managementExposureExclude the effective, BootUI-default-skipping value of
 *     {@code management.endpoints.web.exposure.exclude}, or {@code null} if not configured
 * @param managementServerPortConfigured whether a separate enabled management listener is configured;
 *     an equal fixed port is not separate and -1 disables HTTP
 * @param activeProfiles the application's active Spring profiles
 * @param securityDebugEnabled legacy compatibility signal for the unsupported
 *     {@code spring.security.debug} property; the Spring WebFlux collector always supplies
 *     {@code false}
 * @param oauth2JwtStaticPublicKeyConfigured whether
 *     {@code spring.security.oauth2.resourceserver.jwt.public-key-location} configures a static public
 *     key instead of a remotely rotatable JWKS
 * @param oauth2JwtIssuerUsesPlainHttp whether the configured issuer URI uses plain HTTP
 * @param oauth2JwtJwkSetUsesPlainHttp whether the configured JWKS URI uses plain HTTP
 * @param securityLoggingLevel a verbose configured Spring Security logger or inherited level, including
 *     explicit child overrides rather than only the first configured parent
 * @param suspectedHardcodedSecretKeys property keys (never values) whose names suggest a credential
 *     or secret and whose values appear to be literal strings rather than placeholder references
 * @param oauth2OpaqueTokenIntrospectionUsesPlainHttp whether the configured opaque-token
 *     introspection URI uses plain HTTP
 * @param managementEnvShowValuesAlways whether the host explicitly configures
 *     {@code management.endpoint.env.show-values=always}
 * @param managementConfigPropsShowValuesAlways whether the host explicitly configures
 *     {@code management.endpoint.configprops.show-values=always}
 * @param managementEnvWebExposed whether the effective Actuator include/exclude and access settings
 *     make the {@code env} endpoint web-accessible
 * @param managementConfigPropsWebExposed whether the effective Actuator include/exclude and access
 *     settings make the {@code configprops} endpoint web-accessible
 */
public record ReactiveSecurityEnvironmentSnapshot(
        boolean globalTlsConfigured,
        String managementExposureInclude,
        String managementExposureExclude,
        boolean managementServerPortConfigured,
        List<String> activeProfiles,
        boolean securityDebugEnabled,
        boolean oauth2JwtStaticPublicKeyConfigured,
        boolean oauth2JwtIssuerUsesPlainHttp,
        boolean oauth2JwtJwkSetUsesPlainHttp,
        String securityLoggingLevel,
        Set<String> suspectedHardcodedSecretKeys,
        boolean oauth2OpaqueTokenIntrospectionUsesPlainHttp,
        boolean managementEnvShowValuesAlways,
        boolean managementConfigPropsShowValuesAlways,
        boolean managementEnvWebExposed,
        boolean managementConfigPropsWebExposed,
        Set<String> effectiveActuatorEndpoints,
        boolean actuatorObservationComplete,
        Map<String, String> analysisFailures,
        Set<String> incompleteRules,
        boolean globalTlsObserved) {

    public ReactiveSecurityEnvironmentSnapshot(
            boolean globalTlsConfigured,
            String managementExposureInclude,
            String managementExposureExclude,
            boolean managementServerPortConfigured,
            List<String> activeProfiles,
            boolean securityDebugEnabled,
            boolean oauth2JwtStaticPublicKeyConfigured,
            boolean oauth2JwtIssuerUsesPlainHttp,
            boolean oauth2JwtJwkSetUsesPlainHttp,
            String securityLoggingLevel,
            Set<String> suspectedHardcodedSecretKeys,
            boolean oauth2OpaqueTokenIntrospectionUsesPlainHttp,
            boolean managementEnvShowValuesAlways,
            boolean managementConfigPropsShowValuesAlways,
            boolean managementEnvWebExposed,
            boolean managementConfigPropsWebExposed,
            Set<String> effectiveActuatorEndpoints,
            boolean actuatorObservationComplete,
            Map<String, String> analysisFailures,
            Set<String> incompleteRules) {
        this(
                globalTlsConfigured,
                managementExposureInclude,
                managementExposureExclude,
                managementServerPortConfigured,
                activeProfiles,
                securityDebugEnabled,
                oauth2JwtStaticPublicKeyConfigured,
                oauth2JwtIssuerUsesPlainHttp,
                oauth2JwtJwkSetUsesPlainHttp,
                securityLoggingLevel,
                suspectedHardcodedSecretKeys,
                oauth2OpaqueTokenIntrospectionUsesPlainHttp,
                managementEnvShowValuesAlways,
                managementConfigPropsShowValuesAlways,
                managementEnvWebExposed,
                managementConfigPropsWebExposed,
                effectiveActuatorEndpoints,
                actuatorObservationComplete,
                analysisFailures,
                incompleteRules,
                true);
    }

    public ReactiveSecurityEnvironmentSnapshot(
            boolean globalTlsConfigured,
            String managementExposureInclude,
            String managementExposureExclude,
            boolean managementServerPortConfigured,
            List<String> activeProfiles,
            boolean securityDebugEnabled,
            boolean oauth2JwtStaticPublicKeyConfigured,
            boolean oauth2JwtIssuerUsesPlainHttp,
            boolean oauth2JwtJwkSetUsesPlainHttp,
            String securityLoggingLevel,
            Set<String> suspectedHardcodedSecretKeys,
            boolean oauth2OpaqueTokenIntrospectionUsesPlainHttp,
            boolean managementEnvShowValuesAlways,
            boolean managementConfigPropsShowValuesAlways,
            boolean managementEnvWebExposed,
            boolean managementConfigPropsWebExposed,
            Set<String> effectiveActuatorEndpoints,
            boolean actuatorObservationComplete) {
        this(
                globalTlsConfigured,
                managementExposureInclude,
                managementExposureExclude,
                managementServerPortConfigured,
                activeProfiles,
                securityDebugEnabled,
                oauth2JwtStaticPublicKeyConfigured,
                oauth2JwtIssuerUsesPlainHttp,
                oauth2JwtJwkSetUsesPlainHttp,
                securityLoggingLevel,
                suspectedHardcodedSecretKeys,
                oauth2OpaqueTokenIntrospectionUsesPlainHttp,
                managementEnvShowValuesAlways,
                managementConfigPropsShowValuesAlways,
                managementEnvWebExposed,
                managementConfigPropsWebExposed,
                effectiveActuatorEndpoints,
                actuatorObservationComplete,
                Map.of(),
                Set.of());
    }

    public ReactiveSecurityEnvironmentSnapshot(
            boolean globalTlsConfigured,
            String managementExposureInclude,
            String managementExposureExclude,
            boolean managementServerPortConfigured,
            List<String> activeProfiles,
            boolean securityDebugEnabled,
            boolean oauth2JwtStaticPublicKeyConfigured,
            boolean oauth2JwtIssuerUsesPlainHttp,
            boolean oauth2JwtJwkSetUsesPlainHttp,
            String securityLoggingLevel,
            Set<String> suspectedHardcodedSecretKeys,
            boolean oauth2OpaqueTokenIntrospectionUsesPlainHttp,
            boolean managementEnvShowValuesAlways,
            boolean managementConfigPropsShowValuesAlways,
            boolean managementEnvWebExposed,
            boolean managementConfigPropsWebExposed) {
        this(
                globalTlsConfigured,
                managementExposureInclude,
                managementExposureExclude,
                managementServerPortConfigured,
                activeProfiles,
                securityDebugEnabled,
                oauth2JwtStaticPublicKeyConfigured,
                oauth2JwtIssuerUsesPlainHttp,
                oauth2JwtJwkSetUsesPlainHttp,
                securityLoggingLevel,
                suspectedHardcodedSecretKeys,
                oauth2OpaqueTokenIntrospectionUsesPlainHttp,
                managementEnvShowValuesAlways,
                managementConfigPropsShowValuesAlways,
                managementEnvWebExposed,
                managementConfigPropsWebExposed,
                null,
                true);
    }

    public ReactiveSecurityEnvironmentSnapshot {
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        suspectedHardcodedSecretKeys =
                suspectedHardcodedSecretKeys == null ? Set.of() : Set.copyOf(suspectedHardcodedSecretKeys);
        effectiveActuatorEndpoints = effectiveActuatorEndpoints == null ? null : Set.copyOf(effectiveActuatorEndpoints);
        analysisFailures = analysisFailures == null ? Map.of() : Map.copyOf(analysisFailures);
        incompleteRules = incompleteRules == null ? Set.of() : Set.copyOf(incompleteRules);
    }

    /** Compatibility constructor for snapshots created before effective Actuator exposure signals. */
    public ReactiveSecurityEnvironmentSnapshot(
            boolean globalTlsConfigured,
            String managementExposureInclude,
            String managementExposureExclude,
            boolean managementServerPortConfigured,
            List<String> activeProfiles,
            boolean securityDebugEnabled,
            boolean oauth2JwtStaticPublicKeyConfigured,
            boolean oauth2JwtIssuerUsesPlainHttp,
            boolean oauth2JwtJwkSetUsesPlainHttp,
            String securityLoggingLevel,
            Set<String> suspectedHardcodedSecretKeys,
            boolean oauth2OpaqueTokenIntrospectionUsesPlainHttp,
            boolean managementEnvShowValuesAlways,
            boolean managementConfigPropsShowValuesAlways) {
        this(
                globalTlsConfigured,
                managementExposureInclude,
                managementExposureExclude,
                managementServerPortConfigured,
                activeProfiles,
                securityDebugEnabled,
                oauth2JwtStaticPublicKeyConfigured,
                oauth2JwtIssuerUsesPlainHttp,
                oauth2JwtJwkSetUsesPlainHttp,
                securityLoggingLevel,
                suspectedHardcodedSecretKeys,
                oauth2OpaqueTokenIntrospectionUsesPlainHttp,
                managementEnvShowValuesAlways,
                managementConfigPropsShowValuesAlways,
                false,
                false);
    }

    /** Compatibility constructor for observations created before the additional Boot 4 signals. */
    public ReactiveSecurityEnvironmentSnapshot(
            boolean globalTlsConfigured,
            String managementExposureInclude,
            String managementExposureExclude,
            boolean managementServerPortConfigured,
            List<String> activeProfiles,
            boolean securityDebugEnabled,
            boolean oauth2JwtStaticPublicKeyConfigured,
            boolean oauth2JwtIssuerUsesPlainHttp,
            boolean oauth2JwtJwkSetUsesPlainHttp,
            String securityLoggingLevel,
            Set<String> suspectedHardcodedSecretKeys) {
        this(
                globalTlsConfigured,
                managementExposureInclude,
                managementExposureExclude,
                managementServerPortConfigured,
                activeProfiles,
                securityDebugEnabled,
                oauth2JwtStaticPublicKeyConfigured,
                oauth2JwtIssuerUsesPlainHttp,
                oauth2JwtJwkSetUsesPlainHttp,
                securityLoggingLevel,
                suspectedHardcodedSecretKeys,
                false,
                false,
                false);
    }

    /** A snapshot with no signals set, for tests and the empty/DISABLED path. */
    public static ReactiveSecurityEnvironmentSnapshot empty() {
        return new ReactiveSecurityEnvironmentSnapshot(
                false, null, null, false, List.of(), false, false, false, false, null, Set.of());
    }
}
