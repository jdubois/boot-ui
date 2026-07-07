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
 * Pins the {@code bootui.constellation.*} MicroProfile {@link Config} binding contract used by
 * {@link BootUiEngineProducer#constellationService(Config)} so it stays at parity with the Spring adapter's
 * {@code BootUiProperties.Constellation} defaults. Also exercises the null-safe, comma-splitting
 * {@link BootUiEngineProducer#constellationPeers(Config)} helper shared with {@link QuarkusPanelAvailability}.
 */
class BootUiEngineProducerConstellationConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withConverter(Duration.class, 100, new DurationConverter())
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void appliesSpringDefaultsWhenUnset() {
        Config config = config(Map.of());

        assertThat(config.getOptionalValue("bootui.constellation.enabled", Boolean.class)
                        .orElse(Boolean.FALSE))
                .isFalse();
        assertThat(config.getOptionalValue("bootui.constellation.request-timeout", Duration.class)
                        .orElse(Duration.ofSeconds(2)))
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(BootUiEngineProducer.constellationPeers(config)).isEmpty();
    }

    @Test
    void bindsOverridesIncludingDurationAndPeerList() {
        Config config = config(Map.of(
                "bootui.constellation.enabled", "true",
                "bootui.constellation.request-timeout", "5s",
                "bootui.constellation.peers", "http://localhost:8081,http://localhost:8082"));

        assertThat(config.getValue("bootui.constellation.enabled", Boolean.class))
                .isTrue();
        assertThat(config.getValue("bootui.constellation.request-timeout", Duration.class))
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(BootUiEngineProducer.constellationPeers(config))
                .containsExactly("http://localhost:8081", "http://localhost:8082");
    }

    @Test
    void peersHelperTrimsWhitespaceAndDropsBlankEntries() {
        Config config =
                config(Map.of("bootui.constellation.peers", " http://localhost:8081 , , http://localhost:8082"));

        List<String> peers = BootUiEngineProducer.constellationPeers(config);

        assertThat(peers).containsExactly("http://localhost:8081", "http://localhost:8082");
    }
}
