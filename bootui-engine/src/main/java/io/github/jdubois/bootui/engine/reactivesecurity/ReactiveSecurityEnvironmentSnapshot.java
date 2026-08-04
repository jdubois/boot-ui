package io.github.jdubois.bootui.engine.reactivesecurity;

import java.util.List;
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
 * @param globalTlsConfigured whether TLS is configured globally (e.g. {@code server.ssl.enabled=true},
 *     a configured key store/bundle/certificate, or a trusted {@code server.forward-headers-strategy})
 * @param managementExposureInclude the effective, BootUI-default-skipping value of
 *     {@code management.endpoints.web.exposure.include}, or {@code null} if not configured
 * @param managementExposureExclude the effective, BootUI-default-skipping value of
 *     {@code management.endpoints.web.exposure.exclude}, or {@code null} if not configured
 * @param managementServerPortConfigured whether {@code management.server.port} is configured
 * @param activeProfiles the application's active Spring profiles
 * @param securityDebugEnabled whether {@code spring.security.debug=true}
 * @param oauth2JwtStaticPublicKeyConfigured whether
 *     {@code spring.security.oauth2.resourceserver.jwt.public-key-location} configures a static public
 *     key instead of a remotely rotatable JWKS
 * @param oauth2JwtIssuerUsesPlainHttp whether the configured issuer URI uses plain HTTP
 * @param oauth2JwtJwkSetUsesPlainHttp whether the configured JWKS URI uses plain HTTP
 * @param securityLoggingLevel the resolved value of {@code logging.level.org.springframework.security}
 *     (falling back to {@code logging.level.org.springframework.security.web})
 * @param suspectedHardcodedSecretKeys property keys (never values) whose names suggest a credential
 *     or secret and whose values appear to be literal strings rather than placeholder references
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
        Set<String> suspectedHardcodedSecretKeys) {

    public ReactiveSecurityEnvironmentSnapshot {
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        suspectedHardcodedSecretKeys =
                suspectedHardcodedSecretKeys == null ? Set.of() : Set.copyOf(suspectedHardcodedSecretKeys);
    }

    /** A snapshot with no signals set, for tests and the empty/DISABLED path. */
    public static ReactiveSecurityEnvironmentSnapshot empty() {
        return new ReactiveSecurityEnvironmentSnapshot(
                false, null, null, false, List.of(), false, false, false, false, null, Set.of());
    }
}
