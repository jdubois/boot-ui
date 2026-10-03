package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.insights.AiUsageByRoute;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code bootui.runtime-insights.ai-token-threshold} is rejected on Quarkus exactly as Spring's property binder rejects
 * it, and at startup rather than at the first Runtime Insights request. Driven through a real SmallRye config so the
 * empty-value case is proven against the converters Quarkus actually applies.
 */
class BootUiEngineProducerRuntimeInsightsConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    private static SmallRyeConfig threshold(String value) {
        return config(Map.of("bootui.runtime-insights.ai-token-threshold", value));
    }

    @Test
    void theTokenThresholdDefaultsToTheEnginesValueOnlyWhenItIsUnset() {
        assertThat(BootUiEngineProducer.runtimeInsightsTokenThreshold(config(Map.of())))
                .isEqualTo(AiUsageByRoute.DEFAULT_TOKEN_THRESHOLD);
        assertThat(BootUiEngineProducer.runtimeInsightsTokenThreshold(threshold("1")))
                .isEqualTo(1);
    }

    @Test
    void aNonPositiveTokenThresholdFailsTheStartupObserverWithSpringsMessage() {
        for (String invalid : new String[] {"0", "-1"}) {
            SmallRyeConfig config = threshold(invalid);
            assertThatThrownBy(() -> new BootUiEngineProducer().validateRuntimeInsightsThreshold(null, config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("bootui.runtime-insights.ai-token-threshold must be positive.");
        }
    }

    @Test
    void anUnreadableTokenThresholdFailsTheStartupObserverRatherThanTakingTheDefault() {
        for (String invalid : new String[] {"1.5", "", "eight thousand"}) {
            SmallRyeConfig config = threshold(invalid);
            assertThatThrownBy(() -> new BootUiEngineProducer().validateRuntimeInsightsThreshold(null, config))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
