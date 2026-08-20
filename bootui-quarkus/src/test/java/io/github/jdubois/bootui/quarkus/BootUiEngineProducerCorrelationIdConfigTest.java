package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.CorrelationIdPolicy;
import io.github.jdubois.bootui.engine.correlation.CorrelationIdSettings;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code bootui.http-exchanges.correlation-id-headers} MicroProfile {@link Config} binding used by
 * {@link BootUiEngineProducer#correlationIdSettings(Config)}. The key name and the accepted/refused outcome are
 * kept unified with the Spring adapter's {@code BootUiProperties.HttpExchanges.correlationIdHeaders}, so the same
 * configuration yields the same captured identifiers on every runtime.
 */
class BootUiEngineProducerCorrelationIdConfigTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void capturesOnlyTheBuiltInHeadersWhenUnset() {
        CorrelationIdSettings settings = new BootUiEngineProducer().correlationIdSettings(config(Map.of()));

        assertThat(settings.headerNames()).containsExactlyElementsOf(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES);
        assertThat(settings.rejectedHeaderNames()).isEmpty();
    }

    @Test
    void appendsConfiguredAdditionalHeaderNames() {
        CorrelationIdSettings settings = new BootUiEngineProducer()
                .correlationIdSettings(
                        config(Map.of("bootui.http-exchanges.correlation-id-headers", "X-Tenant-Trace,X-Job-Id")));

        assertThat(settings.headerNames()).endsWith("x-tenant-trace", "x-job-id");
    }

    @Test
    void refusesCredentialBearingHeaderNamesAndReportsThem() {
        CorrelationIdSettings settings = new BootUiEngineProducer()
                .correlationIdSettings(
                        config(Map.of("bootui.http-exchanges.correlation-id-headers", "Authorization,Cookie")));

        assertThat(settings.headerNames()).containsExactlyElementsOf(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES);
        assertThat(settings.rejectedHeaderNames()).containsExactly("Authorization", "Cookie");
    }
}
