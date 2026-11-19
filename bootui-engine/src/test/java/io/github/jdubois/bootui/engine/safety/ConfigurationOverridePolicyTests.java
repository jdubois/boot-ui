package io.github.jdubois.bootui.engine.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigurationOverridePolicyTests {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "bootui.enabled",
                "BOOTUI_ENABLED",
                "bootui.allowNonLocalhost",
                "BOOTUI_TRUSTED_PROXIES_0",
                "bootui.allowed-hosts[1]",
                "bootui.enabled-profiles[0]",
                "bootui.disabledProfiles",
                "bootui.authentication.token",
                "bootui.mask-secrets",
                "bootui.expose-values",
                "bootui.panels.config.read-only",
                "bootui.panels.config.enabled",
                "bootui.path",
                "bootui.apiPath",
                "bootui.overrides-file",
                "bootui.trust-container-gateway",
                "bootui.read-only",
                "bootui.actuator.expose-defaults",
                "spring.profiles.active",
                "SPRING_PROFILES_INCLUDE_0",
                "spring.profiles.group.local[0]",
                "spring.config.import",
                "SPRING_CONFIG_ADDITIONAL_LOCATION",
                "spring.config.name",
                "management.endpoints.web.exposure.include",
                "management.endpoints.web.path-mapping.env",
                "management.endpoint.health.group.local.showDetails",
                "management.endpoint.env.show-values",
                "management.server.port",
                "management.server.address",
                "management.server.ssl.enabled"
            })
    void rejectsSafetySettingsAndRelaxedAliases(String name) {
        assertThat(ConfigurationOverridePolicy.isProtected(name)).isTrue();
        assertThatThrownBy(() -> ConfigurationOverridePolicy.requireWritable(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BootUI safety settings must be configured outside runtime overrides");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "server.port",
                "spring.datasource.password",
                "bootui.memory.max-classes",
                "bootui.http-exchanges.capacity",
                "sample.greeting"
            })
    void preservesApplicationOverridesAndUnrelatedTuning(String name) {
        assertThat(ConfigurationOverridePolicy.isProtected(name)).isFalse();
    }
}
