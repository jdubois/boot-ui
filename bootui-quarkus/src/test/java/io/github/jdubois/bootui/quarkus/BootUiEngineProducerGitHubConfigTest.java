package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code bootui.github.*} MicroProfile {@link Config} binding contract used by
 * {@link BootUiEngineProducer#gitHubDashboardService(Config)} so it stays at parity with the Spring adapter's
 * {@code BootUiProperties.GitHub} defaults. The producer reads these keys inline; this test exercises the same
 * converters (Quarkus {@link DurationConverter}, {@code Integer}, {@code Boolean}, {@code String[]}) plus the
 * null-safe {@link BootUiEngineProducer#gitHubAllowedApiHosts(Config)} helper.
 */
class BootUiEngineProducerGitHubConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withConverter(Duration.class, 100, new DurationConverter())
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void appliesSpringDefaultsWhenUnset() {
        Config config = config(Map.of());

        assertThat(config.getOptionalValue("bootui.github.api-enabled", Boolean.class)
                        .orElse(Boolean.TRUE))
                .isTrue();
        assertThat(config.getOptionalValue("bootui.github.request-timeout", Duration.class)
                        .orElse(Duration.ofSeconds(5)))
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(config.getOptionalValue("bootui.github.max-pull-requests", Integer.class)
                        .orElse(10))
                .isEqualTo(10);
        assertThat(config.getOptionalValue("bootui.github.max-issues", Integer.class)
                        .orElse(25))
                .isEqualTo(25);
        assertThat(config.getOptionalValue("bootui.github.max-workflow-runs", Integer.class)
                        .orElse(20))
                .isEqualTo(20);
        assertThat(config.getOptionalValue("bootui.github.quota-safety-threshold", Integer.class)
                        .orElse(10))
                .isEqualTo(10);
        assertThat(config.getOptionalValue("bootui.github.max-api-calls", Integer.class)
                        .orElse(17))
                .isEqualTo(17);
        assertThat(BootUiEngineProducer.gitHubAllowedApiHosts(config)).containsExactly("api.github.com");
    }

    @Test
    void bindsOverridesIncludingDurationAndHostList() {
        Config config = config(Map.of(
                "bootui.github.api-enabled", "false",
                "bootui.github.request-timeout", "12s",
                "bootui.github.max-pull-requests", "3",
                "bootui.github.max-issues", "4",
                "bootui.github.max-workflow-runs", "5",
                "bootui.github.quota-safety-threshold", "6",
                "bootui.github.max-api-calls", "7",
                "bootui.github.allowed-api-hosts", "localhost,api.github.com"));

        assertThat(config.getValue("bootui.github.api-enabled", Boolean.class)).isFalse();
        assertThat(config.getValue("bootui.github.request-timeout", Duration.class))
                .isEqualTo(Duration.ofSeconds(12));
        assertThat(config.getValue("bootui.github.max-pull-requests", Integer.class))
                .isEqualTo(3);
        assertThat(config.getValue("bootui.github.max-api-calls", Integer.class))
                .isEqualTo(7);
        assertThat(BootUiEngineProducer.gitHubAllowedApiHosts(config)).containsExactly("localhost", "api.github.com");
    }

    @Test
    void allowedApiHostsHelperReturnsMutationSafeList() {
        Config config = config(Map.of("bootui.github.allowed-api-hosts", "localhost"));

        List<String> hosts = BootUiEngineProducer.gitHubAllowedApiHosts(config);

        assertThat(hosts).containsExactly("localhost");
    }
}
