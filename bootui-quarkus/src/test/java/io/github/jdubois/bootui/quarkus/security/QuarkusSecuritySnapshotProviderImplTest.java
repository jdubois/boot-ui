package io.github.jdubois.bootui.quarkus.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.quarkussecurity.QuarkusSecurityScanner;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import io.quarkus.vertx.http.runtime.PolicyMappingConfig;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.SysPropConfigSource;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class QuarkusSecuritySnapshotProviderImplTest {

    @Test
    void detectsPlainHttpOidcAndJwtEndpointsWithoutRetainingTheirValues() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.oidc.auth-server-url", "http://identity.internal/realms/app",
                "mp.jwt.verify.publickey.location", "http://keys.internal/jwks.json"));

        assertThat(snapshot.insecureIdentityProviderUrl()).isTrue();
    }

    @Test
    void acceptsHttpsIdentityEndpoints() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.oidc.auth-server-url", "https://identity.example/realms/app",
                "mp.jwt.verify.publickey.location", "https://identity.example/jwks.json"));

        assertThat(snapshot.insecureIdentityProviderUrl()).isFalse();
    }

    @Test
    void detectsExplicitIssuerAnyBypass() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.oidc.auth-server-url", "https://identity.example/realms/app",
                "quarkus.oidc.token.issuer", "any"));

        assertThat(snapshot.oidcIssuerAny()).isTrue();
    }

    @Test
    void includesNamedOidcTenantsInSecurityChecks() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.oidc.partner.auth-server-url", "http://identity.internal/realms/partner",
                "quarkus.oidc.partner.application-type", "service",
                "quarkus.oidc.partner.token.issuer", "any"));

        assertThat(snapshot.oidcConfigured()).isTrue();
        assertThat(snapshot.oidcServiceTokenConsumer()).isTrue();
        assertThat(snapshot.oidcAudienceConfigured()).isFalse();
        assertThat(snapshot.insecureIdentityProviderUrl()).isTrue();
        assertThat(snapshot.oidcIssuerAny()).isTrue();
    }

    @Test
    void namedWebAppTenantDoesNotRequireResourceServerAudience() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.oidc.portal.auth-server-url", "https://identity.example/realms/portal",
                "quarkus.oidc.portal.application-type", "web-app"));

        assertThat(snapshot.oidcServiceTokenConsumer()).isFalse();
        assertThat(snapshot.oidcAudienceConfigured()).isTrue();
    }

    @Test
    void aggregatesNamedWebAppHardeningSettings() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.oidc.portal.auth-server-url", "https://identity.example/realms/portal",
                "quarkus.oidc.portal.application-type", "web-app",
                "quarkus.oidc.portal.tls.verification", "none"));

        assertThat(snapshot.oidcApplicationType()).isEqualTo("web-app");
        assertThat(snapshot.oidcTlsVerificationNone()).isTrue();
        assertThat(snapshot.oidcCookieForceSecure()).isFalse();
        assertThat(snapshot.oidcHasClientSecret()).isFalse();
        assertThat(snapshot.oidcPkceRequired()).isFalse();
    }

    @Test
    void ignoresExplicitlyDisabledNamedTenant() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.oidc.legacy.auth-server-url", "http://identity.internal/realms/legacy",
                "quarkus.oidc.legacy.tenant-enabled", "false",
                "quarkus.oidc.legacy.token.issuer", "any"));

        assertThat(snapshot.oidcConfigured()).isFalse();
        assertThat(snapshot.insecureIdentityProviderUrl()).isFalse();
        assertThat(snapshot.oidcIssuerAny()).isFalse();
    }

    @Test
    void discoversOidcTenantsConfiguredForLocalPublicKeyVerification() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.oidc.public-key", "public-key",
                "quarkus.oidc.partner.public-key", "partner-public-key"));

        assertThat(snapshot.oidcConfigured()).isTrue();
        assertThat(snapshot.oidcServiceTokenConsumer()).isTrue();
        assertThat(snapshot.oidcAudienceConfigured()).isFalse();
    }

    @Test
    void derivesEffectiveInsecureRequestDefaultFromClientAuthentication() {
        QuarkusSecuritySnapshot required = snapshot(Map.of("quarkus.http.ssl.client-auth", "required"));
        QuarkusSecuritySnapshot requested = snapshot(Map.of("quarkus.http.ssl.client-auth", "request"));
        QuarkusSecuritySnapshot explicit = snapshot(
                Map.of("quarkus.http.ssl.client-auth", "required", "quarkus.http.insecure-requests", "enabled"));

        assertThat(required.mtls()).isTrue();
        assertThat(required.insecureRequests()).isEqualTo("disabled");
        assertThat(requested.mtls()).isTrue();
        assertThat(requested.insecureRequests()).isEqualTo("enabled");
        assertThat(explicit.insecureRequests()).isEqualTo("enabled");
    }

    @Test
    void requiresProxyAddressForwardingBeforeReportingProxyAwareness() {
        QuarkusSecuritySnapshot allowForwardedOnly = snapshot(Map.of("quarkus.http.proxy.allow-forwarded", "true"));
        QuarkusSecuritySnapshot proxyAddressForwarding =
                snapshot(Map.of("quarkus.http.proxy.proxy-address-forwarding", "true"));

        assertThat(allowForwardedOnly.behindProxy()).isFalse();
        assertThat(proxyAddressForwarding.behindProxy()).isTrue();
    }

    @Test
    void recognizesImplicitBasicAuthForEmbeddedUsersUnlessExplicitlyDisabled() {
        QuarkusSecuritySnapshot inferred = snapshot(Map.of("quarkus.security.users.embedded.enabled", "true"));
        QuarkusSecuritySnapshot disabled =
                snapshot(Map.of("quarkus.security.users.embedded.enabled", "true", "quarkus.http.auth.basic", "false"));

        assertThat(inferred.basicAuth()).isTrue();
        assertThat(disabled.basicAuth()).isFalse();
    }

    @Test
    void recognizesImplicitBasicAuthOnlyForAnEnabledJdbcIdentityStore() {
        QuarkusSecuritySnapshot enabled = snapshot(Map.of("quarkus.security.jdbc.enabled", "true"));
        QuarkusSecuritySnapshot disabled = snapshot(Map.of(
                "quarkus.security.jdbc.enabled",
                "false",
                "quarkus.security.jdbc.principal-query.sql",
                "select password from users"));

        assertThat(enabled.basicAuth()).isTrue();
        assertThat(disabled.basicAuth()).isFalse();
    }

    @Test
    void countsOnlyTlsRegistryBucketsSelectedByTheHttpServer() {
        QuarkusSecuritySnapshot defaultJks = snapshot(Map.of("quarkus.tls.key-store.jks.path", "server.jks"));
        QuarkusSecuritySnapshot unrelatedNamed =
                snapshot(Map.of("quarkus.tls.rest-client.key-store.p12.path", "client.p12"));
        QuarkusSecuritySnapshot selectedNamed = snapshot(Map.of(
                "quarkus.http.tls-configuration-name",
                "server",
                "quarkus.tls.server.key-store.pem.0.cert",
                "server.crt",
                "quarkus.tls.server.key-store.pem.0.key",
                "server.key"));
        QuarkusSecuritySnapshot passwordOnly = snapshot(Map.of("quarkus.tls.key-store.p12.password", "not-a-keystore"));

        assertThat(defaultJks.sslConfigured()).isTrue();
        assertThat(unrelatedNamed.sslConfigured()).isFalse();
        assertThat(selectedNamed.sslConfigured()).isTrue();
        assertThat(selectedNamed.evidence().unknownRules()).doesNotContain("QS-TLS-002");
        assertThat(passwordOnly.sslConfigured()).isFalse();
    }

    @Test
    void tlsOptionDefaultsAreNotMaterialAndDoNotMakeTlsObservationUnknown() {
        var snapshot = snapshot(Map.of(
                "quarkus.tls.key-store.p12.password", "${UNRESOLVED_KEYSTORE_PASSWORD}",
                "quarkus.tls.key-store.jks.alias", "default",
                "quarkus.tls.key-store.pem.order", "server",
                "quarkus.tls.key-store.pem.server.password", "${UNRESOLVED_PEM_PASSWORD}"));
        assertThat(snapshot.sslConfigured()).isFalse();
        assertThat(snapshot.evidence().unknownRules()).doesNotContain("QS-TLS-002");
        assertThat(snapshot.evidence().failures()).isEmpty();
        assertThat(snapshot.evidence().incomplete()).noneMatch(category -> category.contains("TLS"));
    }

    @Test
    void excludesExplicitlyDisabledPermissionPolicies() {
        QuarkusSecuritySnapshot disabled = snapshot(Map.of(
                "quarkus.http.auth.permission.open.policy",
                "permit",
                "quarkus.http.auth.permission.open.paths",
                "/*",
                "quarkus.http.auth.permission.open.enabled",
                "false"));
        QuarkusSecuritySnapshot enabled = snapshot(Map.of(
                "quarkus.http.auth.permission.open.policy", "permit",
                "quarkus.http.auth.permission.open.paths", "/*"));
        QuarkusSecuritySnapshot missingPaths = snapshot(Map.of("quarkus.http.auth.permission.open.policy", "permit"));

        assertThat(disabled.permissions()).isEmpty();
        assertThat(enabled.permissions()).hasSize(1);
        assertThat(missingPaths.permissions()).isEmpty();
    }

    @Test
    void detectsOnlyLiteralSecretsFromApplicationConfiguration() {
        ConfigSource application = new PropertiesConfigSource(
                Map.of(
                        "quarkus.datasource.password",
                        "db-secret",
                        "quarkus.oidc.token.issuer",
                        "https://issuer.example",
                        "app.api-token",
                        "literal-token",
                        "app.external-secret",
                        "${EXTERNAL_SECRET}",
                        "%dev.app.password",
                        "dev-only",
                        "%prod.app.password",
                        "prod-secret"),
                "application.properties",
                1000);
        ConfigSource environment = new PropertiesConfigSource(
                Map.of("EXTERNAL_SECRET", "from-env", "app.access-token", "env-token"), "EnvConfigSource", 1100);

        QuarkusSecuritySnapshot snapshot = snapshot(application, environment);

        assertThat(snapshot.suspectedSecretKeys())
                .containsExactly("%prod.app.password", "app.api-token", "quarkus.datasource.password");
    }

    @Test
    void ignoresSystemPropertySecretValues() {
        QuarkusSecuritySnapshot snapshot = snapshot(
                new PropertiesConfigSource(Map.of("app.client-secret", "runtime-secret"), "SysPropConfigSource", 1000));

        assertThat(snapshot.suspectedSecretKeys()).isEmpty();
    }

    @Test
    void stillDetectsApplicationLiteralOverriddenByAnEnvironmentValue() {
        ConfigSource application =
                new PropertiesConfigSource(Map.of("app.password", "committed-secret"), "application.properties", 1000);
        ConfigSource environment =
                new PropertiesConfigSource(Map.of("app.password", "runtime-secret"), "EnvConfigSource", 1100);

        QuarkusSecuritySnapshot snapshot = snapshot(application, environment);

        assertThat(snapshot.suspectedSecretKeys()).containsExactly("app.password");
    }

    @Test
    void detectsPlainTextEmbeddedPasswordsOnlyWhenExplicitlyEnabled() {
        assertThat(snapshot(Map.of(
                                "quarkus.security.users.embedded.enabled",
                                "true",
                                "quarkus.security.users.embedded.plain-text",
                                "true"))
                        .embeddedUsersPlainText())
                .isTrue();
        assertThat(snapshot(Map.of("quarkus.security.users.embedded.enabled", "true"))
                        .embeddedUsersPlainText())
                .isFalse();
    }

    @Test
    void detectsTlsHostnameVerificationDisabledAcrossRegistryAndOidc() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.tls.hostname-verification-algorithm",
                "NONE",
                "quarkus.tls.client.hostname-verification-algorithm",
                "none",
                "quarkus.oidc.partner.auth-server-url",
                "https://identity.example/realms/partner",
                "quarkus.oidc.partner.tls.verification",
                "certificate-validation"));

        assertThat(snapshot.tlsHostnameVerificationDisabled())
                .containsExactlyInAnyOrder(
                        "default TLS registry bucket", "named TLS registry declaration", "OIDC tenant declaration");
    }

    @Test
    void namedOidcTlsRegistryOverridesLegacyVerificationSetting() {
        QuarkusSecuritySnapshot snapshot = snapshot(Map.of(
                "quarkus.oidc.auth-server-url",
                "https://identity.example/realms/app",
                "quarkus.oidc.tls.verification",
                "certificate-validation",
                "quarkus.oidc.tls.tls-configuration-name",
                "secure"));

        assertThat(snapshot.tlsHostnameVerificationDisabled()).isEmpty();
    }

    @Test
    void detectsNonApplicationEndpointsMergedIntoCustomHttpRoot() {
        QuarkusSecuritySnapshot defaults = snapshot(Map.of());
        QuarkusSecuritySnapshot merged = snapshot(Map.of(
                "quarkus.http.root-path", "/api",
                "quarkus.http.non-application-root-path", "${quarkus.http.root-path}"));
        QuarkusSecuritySnapshot sameRelativeSegment =
                snapshot(Map.of("quarkus.http.root-path", "/q", "quarkus.http.non-application-root-path", "q"));

        assertThat(defaults.nonApplicationRootPath()).isEqualTo("q");
        assertThat(defaults.nonApplicationRootPathMerged()).isFalse();
        assertThat(merged.nonApplicationRootPath()).isEqualTo("/api");
        assertThat(merged.nonApplicationRootPathMerged()).isTrue();
        assertThat(sameRelativeSegment.nonApplicationRootPathMerged()).isFalse();
    }

    @Test
    void productionDefaultRolesDoNotRewriteCurrentRuntime() {
        QuarkusSecuritySnapshot snapshot =
                snapshot(Map.of("%prod.quarkus.security.jaxrs.default-roles-allowed", "admin"));

        assertThat(snapshot.defaultRolesAllowed()).isFalse();
    }

    @Test
    void regexOriginsDefaultToCredentialsAndExplicitFalseWins() {
        var regex = snapshot(
                Map.of("quarkus.http.cors.enabled", "true", "quarkus.http.cors.origins", "/.*/,https://app.example"));
        assertThat(regex.corsCredentials()).isTrue();
        assertThat(QuarkusSecurityScanner.usingSnapshot(() -> regex, Clock.systemUTC())
                        .scan()
                        .results())
                .anyMatch(result -> result.id().equals("QS-CORS-002"));
        var explicit = snapshot(Map.of(
                "quarkus.http.cors.enabled",
                "true",
                "quarkus.http.cors.origins",
                "/.*/",
                "quarkus.http.cors.access-control-allow-credentials",
                "false"));
        assertThat(explicit.corsCredentials()).isFalse();
        assertThat(snapshot(Map.of("quarkus.http.cors", "true", "quarkus.http.cors.enabled", "false"))
                        .corsEnabled())
                .isFalse();
    }

    @Test
    void literalWildcardInsideListIsNotUniversalButRegexIs() {
        var mixed = snapshot(
                Map.of("quarkus.http.cors.enabled", "true", "quarkus.http.cors.origins", "*,https://app.example"));
        assertThat(QuarkusSecurityScanner.usingSnapshot(() -> mixed, Clock.systemUTC())
                        .scan()
                        .results())
                .noneMatch(result ->
                        result.id().equals("QS-CORS-001") || result.id().equals("QS-CORS-002"));
    }

    @Test
    void oidcSentinelsAreExactAndAudienceAnyMustBeSingleton() {
        var sentinel = snapshot(Map.of(
                "quarkus.oidc.public-key",
                "public-key",
                "quarkus.oidc.token.issuer",
                "ANY",
                "quarkus.oidc.token.audience",
                "any"));
        assertThat(sentinel.oidcIssuerAny()).isFalse();
        assertThat(sentinel.oidcAudienceConfigured()).isFalse();
        assertThat(snapshot(Map.of(
                                "quarkus.oidc.public-key", "public-key", "quarkus.oidc.token.audience", "any,service"))
                        .oidcAudienceConfigured())
                .isTrue();
        assertThat(snapshot(Map.of("quarkus.oidc.public-key", "public-key", "quarkus.oidc.token.audience", "any,"))
                        .oidcAudienceConfigured())
                .isFalse();
    }

    @Test
    void indexedAudiencePermissionsAndHeaderMethodsPreserveListSemantics() {
        var snapshot = snapshot(Map.of(
                "quarkus.oidc.public-key", "public-key",
                "quarkus.oidc.token.audience[0]", "service",
                "quarkus.http.auth.permission.api.policy", "authenticated",
                "quarkus.http.auth.permission.api.paths[0]", "/api/*",
                "quarkus.http.auth.permission.api.methods[0]", "GET",
                "quarkus.http.header.\"X-Content-Type-Options\".value", "nosniff",
                "quarkus.http.header.\"X-Content-Type-Options\".methods[0]", "GET"));
        assertThat(snapshot.oidcAudienceConfigured()).isTrue();
        assertThat(snapshot.permissions()).singleElement().satisfies(permission -> {
            assertThat(permission.paths()).isEqualTo("/api/*");
            assertThat(permission.methods()).isEqualTo("GET");
        });
        assertThat(snapshot.evidence().unknownRules()).contains("QS-HDR-006");
    }

    @Test
    void staleOidcAndJwtConfigurationWithAbsentCapabilityIsIgnored() {
        var disabled = snapshot(Map.of(
                "bootui.internal.sec.oidc-present",
                "false",
                "bootui.internal.sec.jwt-present",
                "false",
                "quarkus.oidc.auth-server-url",
                "http://issuer.invalid",
                "mp.jwt.verify.publickey.location",
                "http://keys.invalid"));
        assertThat(disabled.oidcConfigured()).isFalse();
        assertThat(disabled.jwtConfigured()).isFalse();
        assertThat(disabled.insecureIdentityProviderUrl()).isFalse();
    }

    @Test
    void alternateSmallRyeKeyLocationOverridesMicroProfileLocation() {
        var secure = snapshot(Map.of(
                "mp.jwt.verify.publickey.location",
                "http://ignored.invalid",
                "smallrye.jwt.verify.key.location",
                "https://keys.example/jwks"));
        assertThat(secure.jwtConfigured()).isTrue();
        assertThat(secure.insecureIdentityProviderUrl()).isFalse();
        assertThat(snapshot(Map.of("smallrye.jwt.verify.key.location", "http://keys.invalid"))
                        .insecureIdentityProviderUrl())
                .isTrue();
    }

    @Test
    void oidcCredentialProvidersAndJwtAuthenticationAreNotPublicClients() {
        for (String setting : java.util.List.of(
                "credentials.client-secret.provider.key",
                "credentials.jwt.key-file",
                "credentials.jwt.secret-provider.key")) {
            var configured = snapshot(Map.of(
                    "quarkus.oidc.public-key",
                    "public-key",
                    "quarkus.oidc.application-type",
                    "web-app",
                    "quarkus.oidc." + setting,
                    "${REMOTE_SECRET}"));
            assertThat(configured.oidcHasClientSecret()).as(setting).isTrue();
            assertThat(configured.oidcPkceRequired()).as(setting).isTrue();
        }
    }

    @Test
    void providerPresetsContributeTypePkceAndIssuerDefaultsWithoutFactoryCalls() {
        var spotify = snapshot(Map.of("quarkus.oidc.provider", "spotify"));
        assertThat(spotify.oidcConfigured()).isTrue();
        assertThat(spotify.oidcApplicationType()).isEqualTo("web-app");
        assertThat(spotify.oidcPkceRequired()).isTrue();
        assertThat(snapshot(Map.of(
                                "quarkus.oidc.provider",
                                "twitter",
                                "quarkus.oidc.authentication.pkce-required",
                                "false"))
                        .oidcPkceRequired())
                .isFalse();
        assertThat(snapshot(Map.of("quarkus.oidc.provider", "microsoft")).oidcIssuerAny())
                .isTrue();
        assertThat(snapshot(Map.of("quarkus.oidc.provider", "unknown"))
                        .evidence()
                        .unknownRules())
                .contains("QS-OIDC-003");
    }

    @Test
    void incompleteListenerMaterialIsNotHttpsAndCookieDependsOnAcceptedHttp() {
        var incomplete = snapshot(Map.of("quarkus.http.ssl.certificate.files", "server.crt"));
        assertThat(incomplete.sslConfigured()).isFalse();
        assertThat(incomplete.evidence().unknownRules()).contains("QS-TLS-002");
        var cookie = snapshot(Map.of(
                "quarkus.http.ssl.certificate.key-store-file",
                "server.p12",
                "quarkus.oidc.public-key",
                "public-key",
                "quarkus.oidc.application-type",
                "web-app",
                "quarkus.http.insecure-requests",
                "enabled"));
        assertThat(QuarkusSecurityScanner.usingSnapshot(() -> cookie, Clock.systemUTC())
                        .scan()
                        .results())
                .anyMatch(result -> result.id().equals("QS-OIDC-002"));
    }

    @Test
    void productionFalseOverridesBaseTrueAndDoesNotChangeCurrentDefaults() {
        var snapshot = snapshot(Map.of(
                "bootui.internal.sec.grpc-present", "true",
                "quarkus.grpc.server.enable-reflection-service", "true",
                "%prod.quarkus.grpc.server.enable-reflection-service", "false",
                "quarkus.swagger-ui.always-include", "true",
                "%prod.quarkus.swagger-ui.always-include", "false",
                "quarkus.management.enabled", "true",
                "%prod.quarkus.management.enabled", "false",
                "%prod.quarkus.security.jaxrs.deny-unannotated-endpoints", "true"));
        assertThat(snapshot.grpcReflectionEnabledInProd()).isFalse();
        assertThat(snapshot.swaggerUiAlwaysInclude()).isFalse();
        assertThat(snapshot.managementHostUnpinnedForProd()).isFalse();
        assertThat(snapshot.denyUnannotatedEndpoints()).isFalse();
    }

    @Test
    void headersAreCaseInsensitiveValueValidatedAndScopeRemainsUnknown() {
        var invalid = snapshot(Map.of(
                "quarkus.http.header.\"x-content-type-options\".value", "incorrect",
                "quarkus.http.header.\"content-security-policy\".value", "script-src 'self'",
                "quarkus.http.header.\"x-frame-options\".value", "ALLOWALL"));
        assertThat(invalid.cspHeader()).isTrue();
        assertThat(invalid.xFrameOptionsHeader()).isFalse();
        assertThat(invalid.xContentTypeOptionsHeader()).isFalse();
        var scoped = snapshot(Map.of(
                "quarkus.http.header.\"X-Content-Type-Options\".value",
                "nosniff",
                "quarkus.http.header.\"X-Content-Type-Options\".methods",
                "GET"));
        assertThat(scoped.evidence().unknownRules()).contains("QS-HDR-006");
        assertThat(snapshot(Map.of("quarkus.http.header.\"x-content-type-options\".value", "NoSnIfF"))
                        .xContentTypeOptionsHeader())
                .isTrue();
    }

    @Test
    void cspEnforcementAndCustomWriterOrderingAreEstablishedOutsideTheParser() {
        var both = snapshot(Map.of(
                "quarkus.http.header.\"Content-Security-Policy\".value", "script-src 'self'; frame-ancestors 'none'",
                "quarkus.http.header.\"Content-Security-Policy-Report-Only\".value", "script-src *"));
        assertThat(both.cspHeaderValue()).isEqualTo("script-src 'self'; frame-ancestors 'none'");
        assertThat(QuarkusSecurityScanner.usingSnapshot(() -> both, Clock.systemUTC())
                        .scan()
                        .results())
                .noneMatch(result -> result.id().equals("QS-HDR-002"));
        assertThat(snapshot(Map.of(
                                "quarkus.http.header.\"Content-Security-Policy-Report-Only\".value",
                                "script-src 'self'; frame-ancestors 'none'"))
                        .cspHeader())
                .isFalse();
        var custom = snapshot(Map.of(
                "bootui.internal.sec.custom-headers",
                "true",
                "quarkus.http.header.\"Content-Security-Policy\".value",
                "script-src *"));
        assertThat(custom.evidence().unknownRules()).contains("QS-HDR-002");
        assertThat(QuarkusSecurityScanner.usingSnapshot(() -> custom, Clock.systemUTC())
                        .scan()
                        .results())
                .noneMatch(result -> result.id().equals("QS-HDR-002"));
    }

    @Test
    void exactJdbcNamespaceAndActiveStoreAreRequired() {
        assertThat(snapshot(Map.of("unrelated.principal-query.clear-password-mapper.enabled", "true"))
                        .jdbcClearPasswordMapperEnabled())
                .isFalse();
        assertThat(snapshot(Map.of(
                                "quarkus.security.jdbc.enabled",
                                "true",
                                "quarkus.security.jdbc.principal-query.users.clear-password-mapper.enabled",
                                "true"))
                        .jdbcClearPasswordMapperEnabled())
                .isTrue();
        assertThat(snapshot(Map.of(
                                "quarkus.security.jdbc.enabled",
                                "false",
                                "quarkus.security.jdbc.principal-query.clear-password-mapper.enabled",
                                "true"))
                        .jdbcClearPasswordMapperEnabled())
                .isFalse();
    }

    @Test
    void idleTimeoutBoundaryAndInvalidDurationAreExplicit() {
        assertThat(snapshot(Map.of("quarkus.http.auth.form.timeout", "PT7H59M59S"))
                        .formSessionTimeoutExcessive())
                .isFalse();
        assertThat(snapshot(Map.of("quarkus.http.auth.form.timeout", "8h")).formSessionTimeoutExcessive())
                .isTrue();
        var invalid = snapshot(
                Map.of("quarkus.http.auth.form.timeout", "secret-invalid-duration", "quarkus.http.auth.basic", "true"));
        var report = QuarkusSecurityScanner.usingSnapshot(() -> invalid, Clock.systemUTC())
                .scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).anyMatch(result -> result.id().equals("QS-AUTH-002"));
        assertThat(report.analysisErrors())
                .isNotEmpty()
                .allMatch(result -> result.status().equals("ERROR"));
        assertThat(report.toString()).doesNotContain("secret-invalid-duration");
    }

    @Test
    void kafkaInheritanceUsesChannelThenConnectorThenGlobalAndSkipsOtherConnectors() {
        var inherited = snapshot(Map.of(
                "mp.messaging.incoming.orders.connector", "smallrye-kafka",
                "kafka.sasl.jaas.config", "${BROKER_CREDENTIAL}",
                "kafka.security.protocol", "SASL_SSL",
                "mp.messaging.connector.smallrye-kafka.security.protocol", "SASL_PLAINTEXT",
                "mp.messaging.incoming.events.connector", "smallrye-amqp",
                "mp.messaging.incoming.events.sasl.password", "ignored"));
        assertThat(inherited.insecureMessagingChannels()).containsExactly("Kafka channel declaration (SASL_PLAINTEXT)");
        var overridden = snapshot(Map.of(
                "mp.messaging.incoming.orders.connector", "smallrye-kafka",
                "kafka.sasl.jaas.config", "${BROKER_CREDENTIAL}",
                "kafka.security.protocol", "SASL_PLAINTEXT",
                "mp.messaging.incoming.orders.security.protocol", "SASL_SSL"));
        assertThat(overridden.insecureMessagingChannels()).isEmpty();
    }

    @Test
    void customSourcesAreNeverEnumeratedForSecretHygiene() {
        AtomicInteger enumerations = new AtomicInteger();
        AtomicInteger credentialReads = new AtomicInteger();
        ConfigSource custom = new ConfigSource() {
            public String getName() {
                return "custom-remote";
            }

            public Map<String, String> getProperties() {
                enumerations.incrementAndGet();
                return Map.of();
            }

            public Set<String> getPropertyNames() {
                enumerations.incrementAndGet();
                return Set.of();
            }

            public String getValue(String key) {
                if (key.endsWith(".secret") || key.endsWith(".password")) credentialReads.incrementAndGet();
                return null;
            }
        };
        var config = new SmallRyeConfigBuilder().withSources(custom).build();
        enumerations.set(0);
        credentialReads.set(0);
        var snapshot = new QuarkusSecuritySnapshotProviderImpl(config).snapshot();
        assertThat(enumerations).hasValue(0);
        assertThat(credentialReads).hasValue(0);
        assertThat(snapshot.evidence().incomplete()).contains("custom configuration source inventory");
        assertThat(snapshot.evidence().unknownRules()).contains("QS-AUTH-001");
        assertThat(QuarkusSecurityScanner.usingSnapshot(() -> snapshot, Clock.systemUTC())
                        .scan()
                        .results())
                .noneMatch(result -> result.id().equals("QS-AUTH-001"));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void nativeSystemPropertiesDiscoverOidcWithoutBecomingLocalSecretHygiene() {
        String urlKey = "quarkus.oidc.auth-server-url";
        String secretKey = "advisor.fixture.password";
        String oldUrl = System.getProperty(urlKey);
        String oldSecret = System.getProperty(secretKey);
        try {
            System.setProperty(urlKey, "https://identity.example/realm");
            System.setProperty(secretKey, "not-a-local-file-secret");
            var config = new SmallRyeConfigBuilder()
                    .withSources(flags(), new SysPropConfigSource())
                    .build();
            assertThat(config.getValue(urlKey, String.class)).isEqualTo("https://identity.example/realm");
            var snapshot = new QuarkusSecuritySnapshotProviderImpl(config).snapshot();
            assertThat(snapshot.oidcConfigured()).isTrue();
            assertThat(snapshot.evidence().unknownRules()).doesNotContain("QS-AUTH-001");
            assertThat(snapshot.suspectedSecretKeys()).isEmpty();
            assertThat(QuarkusSecurityScanner.usingSnapshot(() -> snapshot, Clock.systemUTC())
                            .scan()
                            .results())
                    .noneMatch(result -> result.id().equals("QS-AUTH-001"));
        } finally {
            if (oldUrl == null) System.clearProperty(urlKey);
            else System.setProperty(urlKey, oldUrl);
            if (oldSecret == null) System.clearProperty(secretKey);
            else System.setProperty(secretKey, oldSecret);
        }
    }

    @Test
    void nativeEnvironmentNamesDiscoverDefaultAndNamedTenantsWithoutSecretHygiene() {
        for (String tenant : java.util.List.of("", "PARTNER_")) {
            var snapshot = snapshot(new EnvConfigSource(
                    Map.of(
                            "QUARKUS_OIDC_" + tenant + "AUTH_SERVER_URL",
                            "https://identity.example/realm",
                            "ADVISOR_FIXTURE_PASSWORD",
                            "not-a-local-file-secret"),
                    300));
            assertThat(snapshot.oidcConfigured()).isTrue();
            assertThat(snapshot.evidence().unknownRules()).doesNotContain("QS-AUTH-001");
            assertThat(snapshot.suspectedSecretKeys()).isEmpty();
        }
        var disabled = snapshot(new EnvConfigSource(
                Map.of(
                        "QUARKUS_OIDC_AUTH_SERVER_URL", "https://identity.example/realm",
                        "QUARKUS_OIDC_TENANT_ENABLED", "false"),
                300));
        assertThat(disabled.oidcConfigured()).isFalse();
        var absent = snapshot(new EnvConfigSource(Map.of("ADVISOR_FIXTURE_PASSWORD", "not-a-local-file-secret"), 300));
        assertThat(absent.oidcConfigured()).isFalse();
        assertThat(absent.evidence().unknownRules()).doesNotContain("QS-AUTH-001");
    }

    @Test
    void nativePolicyMappingUppercaseDefaultRemainsARecognizedScope() {
        var config = new SmallRyeConfigBuilder()
                .withMapping(NativePermissions.class)
                .withSources(
                        flags(),
                        new PropertiesConfigSource(
                                Map.of(
                                        "quarkus.http.auth.permission.wide.policy", "permit",
                                        "quarkus.http.auth.permission.wide.paths", "/*"),
                                "application.properties",
                                1000))
                .build();
        assertThat(config.getConfigMapping(NativePermissions.class)
                        .permission()
                        .get("wide")
                        .appliesTo()
                        .name())
                .isEqualTo("ALL");
        assertThat(config.getValue("quarkus.http.auth.permission.wide.applies-to", String.class))
                .isEqualTo("ALL");
        var snapshot = new QuarkusSecuritySnapshotProviderImpl(config).snapshot();
        assertThat(snapshot.permissions())
                .singleElement()
                .satisfies(permission -> assertThat(permission.appliesTo()).isEqualTo("all"));
        assertThat(QuarkusSecurityScanner.usingSnapshot(() -> snapshot, Clock.systemUTC())
                        .scan()
                        .results())
                .anyMatch(result -> result.id().equals("QS-AUTHZ-002"));
    }

    @Test
    void nativeBootstrapRegistryDoesNotInvalidateOrdinaryDeclarationInventoryOrRunUnrelatedFactories() {
        var registry = io.quarkus.runtime.ValueRegistryImpl.builder().build();
        AtomicInteger calls = new AtomicInteger();
        registry.registerInfo(io.quarkus.value.registry.ValueRegistry.RuntimeKey.key("advisor.unrelated"), ignored -> {
            calls.incrementAndGet();
            return "unused";
        });
        var builder = new SmallRyeConfigBuilder().withSources(flags());
        io.quarkus.runtime.ValueRegistryConfigSource.customizer(registry).configBuilder(builder);
        var snapshot = new QuarkusSecuritySnapshotProviderImpl(builder.build()).snapshot();
        assertThat(calls).hasValue(0);
        assertThat(snapshot.evidence().unknownRules()).doesNotContain("QS-AUTH-001");
        assertThat(snapshot.evidence().incomplete()).doesNotContain("custom configuration source inventory");
    }

    @Test
    void explicitJaxrsScopeIsKnownAndUnsupportedScopeIsIncomplete() {
        var known = snapshot(Map.of(
                "quarkus.http.auth.permission.rest.policy",
                "permit",
                "quarkus.http.auth.permission.rest.paths",
                "/*",
                "quarkus.http.auth.permission.rest.applies-to",
                "JAXRS"));
        assertThat(known.permissions())
                .singleElement()
                .satisfies(permission -> assertThat(permission.appliesTo()).isEqualTo("jaxrs"));
        assertThat(known.evidence().incomplete()).doesNotContain("unsupported HTTP permission scope");
        var unknown = snapshot(Map.of(
                "quarkus.http.auth.permission.rest.policy",
                "permit",
                "quarkus.http.auth.permission.rest.paths",
                "/*",
                "quarkus.http.auth.permission.rest.applies-to",
                "UNSUPPORTED"));
        assertThat(unknown.evidence().incomplete()).contains("unsupported HTTP permission scope");
        assertThat(unknown.evidence().unknownRules()).contains("QS-AUTHZ-002", "QS-AUTHZ-004");
        var report = QuarkusSecurityScanner.usingSnapshot(() -> unknown, Clock.systemUTC())
                .scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).noneMatch(result -> result.id().equals("QS-AUTHZ-002"));
    }

    @Test
    void nonDefaultListenerPrefixesCannotProveRawEndpointCoverage() {
        for (String key : java.util.List.of("quarkus.http.root-path", "quarkus.rest.path")) {
            var prefixed = snapshot(Map.of(key, "/api"));
            assertThat(prefixed.evidence().incomplete()).contains("non-default REST listener prefix");
            assertThat(prefixed.evidence().unknownRules()).contains("QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
            var ordinary = snapshot(Map.of(key, "/"));
            assertThat(ordinary.evidence().incomplete()).doesNotContain("non-default REST listener prefix");
        }
    }

    @ConfigMapping(prefix = "quarkus.http.auth")
    interface NativePermissions {
        Map<String, PolicyMappingConfig> permission();
    }

    private static QuarkusSecuritySnapshot snapshot(Map<String, String> properties) {
        return snapshot(new PropertiesConfigSource(properties, "application.properties", 1000));
    }

    private static QuarkusSecuritySnapshot snapshot(ConfigSource... sources) {
        var config = new SmallRyeConfigBuilder()
                .withSources(sources)
                .withSources(flags())
                .build();
        return new QuarkusSecuritySnapshotProviderImpl(config).snapshot();
    }

    private static ConfigSource flags() {
        return new PropertiesConfigSource(
                Map.of(
                        "bootui.internal.sec.security-present", "true",
                        "bootui.internal.sec.oidc-present", "true",
                        "bootui.internal.sec.jwt-present", "true",
                        "bootui.internal.sec.jdbc-present", "true",
                        "bootui.internal.sec.properties-present", "true",
                        "bootui.internal.sec.kafka-present", "true",
                        "bootui.internal.sec.openapi-present", "true",
                        "bootui.internal.sec.health-present", "true",
                        "bootui.internal.sec.grpc-services", "true",
                        "bootui.internal.sec.endpoint-metadata", "true"),
                "application.properties",
                1);
    }
}
