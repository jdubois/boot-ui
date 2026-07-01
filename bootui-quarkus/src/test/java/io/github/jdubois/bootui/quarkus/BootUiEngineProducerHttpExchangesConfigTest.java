package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code bootui.http-exchanges.max-exchanges} MicroProfile {@link Config} binding used by
 * {@link BootUiEngineProducer#httpExchangeBuffer(Config)}. The key name and default (200) are kept unified
 * with the Spring adapter's {@code BootUiProperties.HttpExchanges.maxExchanges}, so the same
 * {@code bootui.http-exchanges.max-exchanges} value sizes the buffer identically on both frameworks.
 */
class BootUiEngineProducerHttpExchangesConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void appliesUnifiedDefaultOf200WhenUnset() {
        HttpExchangeBuffer buffer = new BootUiEngineProducer().httpExchangeBuffer(config(Map.of()));

        assertThat(buffer.capacity()).isEqualTo(200);
    }

    @Test
    void bindsMaxExchangesOverride() {
        HttpExchangeBuffer buffer = new BootUiEngineProducer()
                .httpExchangeBuffer(config(Map.of("bootui.http-exchanges.max-exchanges", "42")));

        assertThat(buffer.capacity()).isEqualTo(42);
    }
}
