package io.github.jdubois.bootui.quarkus.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

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
                "server.crt"));
        QuarkusSecuritySnapshot passwordOnly = snapshot(Map.of("quarkus.tls.key-store.p12.password", "not-a-keystore"));

        assertThat(defaultJks.sslConfigured()).isTrue();
        assertThat(unrelatedNamed.sslConfigured()).isFalse();
        assertThat(selectedNamed.sslConfigured()).isTrue();
        assertThat(passwordOnly.sslConfigured()).isFalse();
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
                        "default TLS registry bucket", "TLS registry bucket client", "OIDC tenant partner");
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
    void recognizesProdScopedDefaultRoles() {
        QuarkusSecuritySnapshot snapshot =
                snapshot(Map.of("%prod.quarkus.security.jaxrs.default-roles-allowed", "admin"));

        assertThat(snapshot.defaultRolesAllowed()).isTrue();
    }

    private static QuarkusSecuritySnapshot snapshot(Map<String, String> properties) {
        return snapshot(new PropertiesConfigSource(properties, "test", 1000));
    }

    private static QuarkusSecuritySnapshot snapshot(ConfigSource... sources) {
        var config = new SmallRyeConfigBuilder().withSources(sources).build();
        return new QuarkusSecuritySnapshotProviderImpl(config).snapshot();
    }
}
