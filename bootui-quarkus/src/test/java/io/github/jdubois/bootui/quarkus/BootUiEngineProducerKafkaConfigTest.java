package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code bootui.kafka.*} MicroProfile {@link Config} bindings used by
 * {@link BootUiEngineProducer#kafkaActivityRecorder(Config)}. The key names and defaults
 * ({@code enabled}/{@code capture-key} {@code true}, {@code max-entries}/{@code max-key-length} {@code 200})
 * are kept unified with the Spring adapter's {@code BootUiProperties.Kafka}, so the same
 * {@code bootui.kafka.*} values size and gate capture identically on both frameworks.
 */
class BootUiEngineProducerKafkaConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void appliesUnifiedDefaultsWhenUnset() {
        KafkaActivityRecorder recorder = new BootUiEngineProducer().kafkaActivityRecorder(config(Map.of()));

        assertThat(recorder.isEnabled()).isTrue();
        assertThat(recorder.isCaptureKey()).isTrue();
        assertThat(recorder.getMaxEntries()).isEqualTo(200);
    }

    @Test
    void bindsEnabledOverride() {
        KafkaActivityRecorder recorder =
                new BootUiEngineProducer().kafkaActivityRecorder(config(Map.of("bootui.kafka.enabled", "false")));

        assertThat(recorder.isEnabled()).isFalse();
    }

    @Test
    void bindsCaptureKeyOverride() {
        KafkaActivityRecorder recorder =
                new BootUiEngineProducer().kafkaActivityRecorder(config(Map.of("bootui.kafka.capture-key", "false")));

        assertThat(recorder.isCaptureKey()).isFalse();
    }

    @Test
    void bindsMaxEntriesOverride() {
        KafkaActivityRecorder recorder =
                new BootUiEngineProducer().kafkaActivityRecorder(config(Map.of("bootui.kafka.max-entries", "42")));

        assertThat(recorder.getMaxEntries()).isEqualTo(42);
    }

    @Test
    void bindsMaxKeyLengthOverride() {
        KafkaActivityRecorder recorder =
                new BootUiEngineProducer().kafkaActivityRecorder(config(Map.of("bootui.kafka.max-key-length", "8")));

        // The key is hashed (SHA-256) then truncated to max-key-length hex chars, which only an applied
        // override can show; "2125b2c3" is hashKey("0123456789ABCDEF", 8).
        recorder.recordProduce("orders", 0, "0123456789ABCDEF", null, true, null);

        assertThat(recorder.recent())
                .singleElement()
                .satisfies(message -> assertThat(message.key()).isEqualTo("2125b2c3"));
    }
}
