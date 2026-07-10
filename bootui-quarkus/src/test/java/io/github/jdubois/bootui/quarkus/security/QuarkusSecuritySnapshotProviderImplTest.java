package io.github.jdubois.bootui.quarkus.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
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

    private static QuarkusSecuritySnapshot snapshot(Map<String, String> properties) {
        var config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
        return new QuarkusSecuritySnapshotProviderImpl(config).snapshot();
    }
}
