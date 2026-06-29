package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral snapshot of a Quarkus application's effective security posture, collected by the
 * Quarkus adapter from MicroProfile config plus build-time annotation counts. Consumed by the engine
 * {@code QuarkusSecurityScanner} to evaluate the Quarkus-native ruleset (see {@code docs/QUARKUS-CHECKS.md}).
 *
 * <p>This carries only neutral values (booleans, strings, counts, and {@link QuarkusSecurityPermission}
 * records) so it never leaks an {@code io.quarkus.*} or framework type into the engine. All fields fail
 * safe: an unknown value is rendered as absent rather than throwing.</p>
 *
 * @param oidcConfigured whether {@code quarkus-oidc} is configured (an auth-server-url is set)
 * @param jwtConfigured whether SmallRye JWT verification is configured
 * @param basicAuth whether {@code quarkus.http.auth.basic} is enabled
 * @param formAuth whether {@code quarkus.http.auth.form} is enabled
 * @param mtls whether mutual-TLS client auth is required
 * @param insecureRequests effective {@code quarkus.http.insecure-requests} ({@code enabled}/{@code redirect}/{@code disabled})
 * @param sslConfigured whether an HTTPS keystore/TLS registry is configured
 * @param corsEnabled whether {@code quarkus.http.cors} is enabled
 * @param corsOrigins the configured allowed origins, or {@code null} (treated as any)
 * @param corsCredentials whether CORS allows credentials
 * @param hstsHeader whether a Strict-Transport-Security response header is configured
 * @param cspHeader whether a Content-Security-Policy response header is configured
 * @param oidcTlsVerificationNone whether OIDC TLS verification is disabled
 * @param swaggerUiAlwaysInclude whether Swagger UI is always included
 * @param openApiAlwaysInclude whether the OpenAPI document is always included
 * @param csrfPresent whether the CSRF reactive extension is present
 * @param permissions configured HTTP permission policies
 * @param rolesAllowedCount number of {@code @RolesAllowed} sites in app classes
 * @param permitAllCount number of {@code @PermitAll} sites
 * @param denyAllCount number of {@code @DenyAll} sites
 * @param authenticatedCount number of {@code @Authenticated} sites
 * @param endpointCount discovered JAX-RS endpoint methods
 * @param securedEndpointCount endpoints carrying an authorization annotation
 * @param suspectedSecretKeys config keys that look like literal secrets (already masked names)
 */
public record QuarkusSecuritySnapshot(
        boolean oidcConfigured,
        boolean jwtConfigured,
        boolean basicAuth,
        boolean formAuth,
        boolean mtls,
        String insecureRequests,
        boolean sslConfigured,
        boolean corsEnabled,
        String corsOrigins,
        boolean corsCredentials,
        boolean hstsHeader,
        boolean cspHeader,
        boolean oidcTlsVerificationNone,
        boolean swaggerUiAlwaysInclude,
        boolean openApiAlwaysInclude,
        boolean csrfPresent,
        List<QuarkusSecurityPermission> permissions,
        int rolesAllowedCount,
        int permitAllCount,
        int denyAllCount,
        int authenticatedCount,
        int endpointCount,
        int securedEndpointCount,
        List<String> suspectedSecretKeys) {

    public QuarkusSecuritySnapshot {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        suspectedSecretKeys = suspectedSecretKeys == null ? List.of() : List.copyOf(suspectedSecretKeys);
    }

    public boolean anyAuthMechanism() {
        return oidcConfigured || jwtConfigured || basicAuth || formAuth || mtls;
    }

    public int annotationCount() {
        return rolesAllowedCount + permitAllCount + denyAllCount + authenticatedCount;
    }
}
