package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.activity.ActivityForwardingSettings;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Duration;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code bootui.activity.forwarding.*} MicroProfile {@link Config} binding used by
 * {@link BootUiEngineProducer#activityForwardingSettings(Config)}, so it stays at parity with the Spring
 * adapter's {@code BootUiProperties.ActivityForwarding} defaults: the same keys resolve to the same
 * {@link ActivityForwardingSettings} on both frameworks. Mirrors {@link
 * BootUiEngineProducerActivityConfigTest}'s structure for the persistence-settings twin.
 */
class BootUiEngineProducerActivityForwardingConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withConverter(Duration.class, 100, new DurationConverter())
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void appliesDefaultsWhenUnset() {
        ActivityForwardingSettings settings = new BootUiEngineProducer().activityForwardingSettings(config(Map.of()));

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.peerBaseUrl()).isNull();
        assertThat(settings.sharedSecret()).isNull();
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.flushInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.bufferMaxEntries()).isEqualTo(500);
        assertThat(settings.captureInterval()).isEqualTo(Duration.ofSeconds(2));
        // No explicit instance-id and no guaranteed HOSTNAME in a test JVM: only the resolved-or-generated
        // contract is pinned here, not a specific value (see ActivityInstanceIdsTests for that logic).
        assertThat(settings.instanceId()).isNotBlank();
    }

    @Test
    void bindsOverridesWithoutTransposition() {
        // Several adjacent fields share a type (three Durations, several Strings), so a positional
        // transposition in the producer would compile and only surface as a wrong value at runtime. Every
        // field below is set to a value distinct from both the default and its same-typed neighbors.
        ActivityForwardingSettings settings = new BootUiEngineProducer()
                .activityForwardingSettings(config(Map.ofEntries(
                        Map.entry("bootui.activity.forwarding.enabled", "true"),
                        Map.entry("bootui.activity.forwarding.peer-base-url", "http://localhost:9090"),
                        Map.entry("bootui.activity.forwarding.shared-secret", "s3cr3t"),
                        Map.entry("bootui.activity.forwarding.connect-timeout", "3s"),
                        Map.entry("bootui.activity.forwarding.request-timeout", "9s"),
                        Map.entry("bootui.activity.forwarding.flush-interval", "11s"),
                        Map.entry("bootui.activity.forwarding.buffer-max-entries", "321"),
                        Map.entry("bootui.activity.forwarding.instance-id", "pinned-instance"),
                        Map.entry("bootui.activity.forwarding.capture-interval", "7s"))));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.peerBaseUrl()).isEqualTo("http://localhost:9090");
        assertThat(settings.sharedSecret()).isEqualTo("s3cr3t");
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(settings.flushInterval()).isEqualTo(Duration.ofSeconds(11));
        assertThat(settings.bufferMaxEntries()).isEqualTo(321);
        assertThat(settings.instanceId()).isEqualTo("pinned-instance");
        assertThat(settings.captureInterval()).isEqualTo(Duration.ofSeconds(7));
    }
}
