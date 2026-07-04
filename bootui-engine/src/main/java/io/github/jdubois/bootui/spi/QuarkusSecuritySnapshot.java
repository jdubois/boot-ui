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
 * @param behindProxy whether the app runs behind a TLS-terminating reverse proxy (forwarded headers trusted)
 * @param jwtIssuerConfigured whether a JWT issuer ({@code mp.jwt.verify.issuer}) is configured
 * @param proactiveAuthDisabled whether {@code quarkus.http.auth.proactive=false}
 * @param oidcAudienceConfigured whether OIDC token audience validation is configured
 * @param oidcApplicationType the {@code quarkus.oidc.application-type} (service/web-app/hybrid; may be empty)
 * @param oidcCookieForceSecure whether OIDC forces the Secure flag on its session cookie
 * @param tlsTrustAll whether outbound TLS certificate validation is globally disabled
 * @param corsMethods the configured {@code quarkus.http.cors.methods}, or {@code null}
 * @param corsHeaders the configured {@code quarkus.http.cors.headers}, or {@code null}
 * @param hstsHeaderValue the raw Strict-Transport-Security header value, or {@code null}
 * @param cspHeaderValue the raw Content-Security-Policy header value, or {@code null}
 * @param xFrameOptionsHeader whether an X-Frame-Options response header is configured
 * @param xContentTypeOptionsHeader whether an X-Content-Type-Options response header is configured
 * @param denyUnannotatedEndpoints whether {@code quarkus.security.jaxrs.deny-unannotated-endpoints=true}
 * @param managementEnabled whether the separate management interface is enabled
 * @param managementHostNonLoopback whether a literal (unresolved) {@code quarkus.management.host} or
 *     {@code %prod.quarkus.management.host} key pins a non-loopback host; read as a raw config key rather
 *     than the profile-resolved value because Quarkus's own built-in default for {@code host} is
 *     profile-dependent ({@code localhost} in dev/test, {@code 0.0.0.0} in prod) and BootUI's Quarkus
 *     advisor only ever runs in dev/test {@code LaunchMode}, so a resolved read would never observe the
 *     prod default it is trying to catch
 * @param managementHostUnpinnedForProd whether the management interface is enabled but neither a literal
 *     {@code quarkus.management.host} nor {@code %prod.quarkus.management.host} key exists at all, meaning
 *     a real prod deployment would silently fall back to Quarkus's own {@code 0.0.0.0} prod default
 * @param jwtAlgorithmUnpinnedForRemoteJwks whether {@code mp.jwt.verify.publickey.location} points at a
 *     remote ({@code http}/{@code https}) JWKS endpoint while {@code mp.jwt.verify.publickey.algorithm} is
 *     left unset, relying on SmallRye JWT's implicit default (RS256-only) instead of explicitly pinning the
 *     expected algorithm(s) for a key source that can rotate/change independently of this application
 * @param jdbcClearPasswordMapperEnabled whether a JDBC principal-query uses the clear-password mapper
 * @param embeddedUsersEnabled whether {@code quarkus.security.users.embedded.enabled=true}
 * @param jwtAudiencesConfigured whether {@code mp.jwt.verify.audiences} is configured
 * @param jwtInlinePublicKey whether {@code mp.jwt.verify.publickey} holds a static inline key (never rotates)
 * @param referrerPolicyHeader whether a Referrer-Policy response header is configured
 * @param permissionsPolicyHeader whether a Permissions-Policy response header is configured
 * @param nonApplicationRootPath the effective {@code quarkus.http.non-application-root-path} (health/metrics/
 *     openapi endpoints), default {@code /q}; used both to check permission coverage and to detect the
 *     Quarkus-specific footgun of collapsing it into the main application path (no Spring equivalent)
 * @param grpcReflectionEnabledInProd whether the gRPC server reflection service is enabled for the prod profile
 *     (Quarkus-specific; Spring has no first-party gRPC equivalent)
 * @param graphqlPresent whether the SmallRye GraphQL capability is present
 * @param graphqlIntrospectionEnabled whether GraphQL schema introspection is enabled (Quarkus-specific;
 *     Spring has no first-party GraphQL equivalent)
 * @param graphqlUiAlwaysInclude whether the GraphQL UI is always included, even outside dev/test
 * @param insecureMessagingChannels the specific Kafka/Reactive-Messaging channel key prefixes that configure
 *     SASL credentials without an encrypted (SASL_SSL/SSL) security protocol, evaluated independently per
 *     channel so one channel's secure protocol can't mask another channel's insecure one (Quarkus-specific;
 *     Spring has no first-party reactive-messaging equivalent); empty when none are insecure
 * @param formCookieHttpOnly whether the form-auth cookie has {@code http-only-cookie} set (Quarkus defaults
 *     this to {@code false}, unlike most frameworks)
 * @param formCookieSameSiteNone whether the form-auth cookie's {@code cookie-same-site} was weakened to {@code none}
 * @param formSessionTimeoutExcessive whether the form-auth session {@code timeout} exceeds a sane bound (8h)
 * @param oidcHasClientSecret whether an OIDC client secret is configured ({@code quarkus.oidc.credentials.secret}
 *     or {@code quarkus.oidc.credentials.client-secret.value}), i.e. this is a confidential rather than a
 *     public client
 * @param oidcPkceRequired whether {@code quarkus.oidc.authentication.pkce-required=true}
 * @param healthUiAlwaysInclude whether {@code quarkus.smallrye-health.ui.always-include=true}
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
        List<String> suspectedSecretKeys,
        boolean behindProxy,
        boolean jwtIssuerConfigured,
        boolean proactiveAuthDisabled,
        boolean oidcAudienceConfigured,
        String oidcApplicationType,
        boolean oidcCookieForceSecure,
        boolean tlsTrustAll,
        String corsMethods,
        String corsHeaders,
        String hstsHeaderValue,
        String cspHeaderValue,
        boolean xFrameOptionsHeader,
        boolean xContentTypeOptionsHeader,
        boolean denyUnannotatedEndpoints,
        boolean managementEnabled,
        boolean managementHostNonLoopback,
        boolean managementHostUnpinnedForProd,
        boolean jwtAlgorithmUnpinnedForRemoteJwks,
        boolean jdbcClearPasswordMapperEnabled,
        boolean embeddedUsersEnabled,
        boolean jwtAudiencesConfigured,
        boolean jwtInlinePublicKey,
        boolean referrerPolicyHeader,
        boolean permissionsPolicyHeader,
        String nonApplicationRootPath,
        boolean grpcReflectionEnabledInProd,
        boolean graphqlPresent,
        boolean graphqlIntrospectionEnabled,
        boolean graphqlUiAlwaysInclude,
        List<String> insecureMessagingChannels,
        boolean formCookieHttpOnly,
        boolean formCookieSameSiteNone,
        boolean formSessionTimeoutExcessive,
        boolean oidcHasClientSecret,
        boolean oidcPkceRequired,
        boolean healthUiAlwaysInclude) {

    public QuarkusSecuritySnapshot {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        suspectedSecretKeys = suspectedSecretKeys == null ? List.of() : List.copyOf(suspectedSecretKeys);
        oidcApplicationType = oidcApplicationType == null ? "" : oidcApplicationType;
        nonApplicationRootPath =
                nonApplicationRootPath == null || nonApplicationRootPath.isBlank() ? "/q" : nonApplicationRootPath;
        insecureMessagingChannels =
                insecureMessagingChannels == null ? List.of() : List.copyOf(insecureMessagingChannels);
    }

    public boolean anyAuthMechanism() {
        return oidcConfigured || jwtConfigured || basicAuth || formAuth || mtls;
    }

    public int annotationCount() {
        return rolesAllowedCount + permitAllCount + denyAllCount + authenticatedCount;
    }
}
