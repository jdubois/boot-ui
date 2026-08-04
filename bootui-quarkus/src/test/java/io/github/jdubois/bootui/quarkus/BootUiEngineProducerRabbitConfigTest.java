package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootUiEngineProducerRabbitConfigTest {

    @Test
    void appliesPrivacyPreservingDefaults() {
        RabbitActivityRecorder recorder = new BootUiEngineProducer().rabbitActivityRecorder(config(Map.of()));

        assertThat(recorder.isEnabled()).isTrue();
        assertThat(recorder.isCaptureCorrelationId()).isFalse();
        assertThat(recorder.getMaxEntries()).isEqualTo(200);
    }

    @Test
    void bindsAllRabbitOverrides() {
        RabbitActivityRecorder recorder = new BootUiEngineProducer()
                .rabbitActivityRecorder(config(Map.of(
                        "bootui.rabbitmq.enabled",
                        "false",
                        "bootui.rabbitmq.capture-correlation-id",
                        "true",
                        "bootui.rabbitmq.max-entries",
                        "42",
                        "bootui.rabbitmq.max-correlation-id-length",
                        "32")));

        assertThat(recorder.isEnabled()).isFalse();
        assertThat(recorder.isCaptureCorrelationId()).isTrue();
        assertThat(recorder.getMaxEntries()).isEqualTo(42);
    }

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }
}
