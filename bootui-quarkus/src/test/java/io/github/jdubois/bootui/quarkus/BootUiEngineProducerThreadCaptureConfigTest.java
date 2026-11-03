package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.threads.ThreadCaptureSettings;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class BootUiEngineProducerThreadCaptureConfigTest {

    @Test
    void defaultsMatchTheSharedEngine() {
        assertThat(new BootUiEngineProducer().threadCaptureSettings(config(Map.of())))
                .isEqualTo(ThreadCaptureSettings.defaults());
    }

    @Test
    void bindsEveryThreadLimitWithoutTransposingSameTypedSettings() {
        var properties = Map.ofEntries(
                Map.entry("bootui.threads.max-threads", "241"),
                Map.entry("bootui.threads.max-stack-depth", "43"),
                Map.entry("bootui.threads.max-owned-locks", "17"),
                Map.entry("bootui.threads.capture-deadline-millis", "2100"),
                Map.entry("bootui.threads.include-synchronizers", "true"),
                Map.entry("bootui.threads.max-captures", "7"),
                Map.entry("bootui.threads.max-retained-bytes", "9437184"),
                Map.entry("bootui.threads.max-capture-bytes", "1048576"),
                Map.entry("bootui.threads.ttl-millis", "120000"),
                Map.entry("bootui.threads.observation-samples", "4"),
                Map.entry("bootui.threads.observation-interval-millis", "1500"));

        assertThat(new BootUiEngineProducer().threadCaptureSettings(config(properties)))
                .isEqualTo(
                        new ThreadCaptureSettings(241, 43, 17, 2100, true, 7, 9_437_184, 1_048_576, 120_000, 4, 1500));
    }

    @Test
    void invalidOverridesFailRatherThanUsingDefaults() {
        assertThatThrownBy(() -> new BootUiEngineProducer()
                        .threadCaptureSettings(config(Map.of("bootui.threads.max-threads", "0"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Config config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }
}
