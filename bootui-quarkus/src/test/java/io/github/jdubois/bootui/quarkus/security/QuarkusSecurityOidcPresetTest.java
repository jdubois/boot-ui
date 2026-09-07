package io.github.jdubois.bootui.quarkus.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.oidc.runtime.OidcTenantConfig;
import io.quarkus.oidc.runtime.providers.KnownOidcProviders;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuarkusSecurityOidcPresetTest {
    @Test
    void nativePresetMetadataAgreesWithNonExecutingAdvisorCapture() {
        for (var provider : OidcTenantConfig.Provider.values()) {
            // Native framework objects are created only as test fixtures, never by the snapshot collector.
            var nativePreset = KnownOidcProviders.provider(provider);
            String name = provider.name().toLowerCase(java.util.Locale.ROOT);
            var config = new SmallRyeConfigBuilder()
                    .withSources(new PropertiesConfigSource(
                            Map.of(
                                    "bootui.internal.sec.oidc-present", "true",
                                    "bootui.internal.sec.endpoint-metadata", "true",
                                    "quarkus.oidc.provider", name),
                            "application.properties",
                            1000))
                    .build();
            var snapshot = new QuarkusSecuritySnapshotProviderImpl(config).snapshot();
            assertThat(snapshot.oidcApplicationType())
                    .as(name)
                    .isEqualTo(nativePreset
                            .applicationType()
                            .orElseThrow()
                            .name()
                            .toLowerCase(java.util.Locale.ROOT)
                            .replace('_', '-'));
            if (!snapshot.oidcHasClientSecret()) {
                assertThat(snapshot.oidcPkceRequired())
                        .as(name)
                        .isEqualTo(nativePreset.authentication().pkceRequired().orElse(false));
            }
            assertThat(snapshot.oidcIssuerAny())
                    .as(name)
                    .isEqualTo(
                            nativePreset.token().issuer().filter("any"::equals).isPresent());
        }
    }
}
