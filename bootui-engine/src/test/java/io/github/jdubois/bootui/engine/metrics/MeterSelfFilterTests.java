package io.github.jdubois.bootui.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MeterSelfFilterTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final MeterSelfFilter filter =
            new MeterSelfFilter(new SelfTelemetryClassifier(true, "/bootui", "/bootui/api"));

    @Test
    void recognizesMetersByPathTagOnly() {
        Meter self = Counter.builder("http.server.requests")
                .tag("uri", "/bootui/api/beans")
                .register(registry);
        Meter host = Counter.builder("http.server.requests")
                .tag("uri", "/api/orders")
                .register(registry);

        assertThat(filter.isBootUiMeter(self)).isTrue();
        assertThat(filter.isBootUiMeter(host)).isFalse();
    }

    @Test
    void ignoresBootUiLookingValuesOnNonPathTags() {
        Meter notAPath =
                Counter.builder("orders.processed").tag("queue", "/bootui/jobs").register(registry);

        assertThat(filter.isBootUiMeter(notAPath))
                .as("a /bootui value on a non-path tag (queue) must not be treated as self traffic")
                .isFalse();
    }

    @Test
    void shouldIncludeHidesSelfMetersWhenExcludingSelf() {
        Meter self = Counter.builder("http.server.requests")
                .tag("uri", "/bootui/api/metrics")
                .register(registry);
        Meter host = Counter.builder("http.server.requests")
                .tag("uri", "/api/orders")
                .register(registry);

        assertThat(filter.shouldIncludeMeter(self)).isFalse();
        assertThat(filter.shouldIncludeMeter(host)).isTrue();
        assertThat(filter.includePredicate().test(self)).isFalse();
        assertThat(filter.includePredicate().test(host)).isTrue();
    }

    @Test
    void disabledClassifierIncludesEverything() {
        MeterSelfFilter disabled = new MeterSelfFilter(SelfTelemetryClassifier.disabled());
        Meter self = Counter.builder("http.server.requests")
                .tag("uri", "/bootui/api/metrics")
                .register(registry);

        assertThat(disabled.isBootUiMeter(self)).isTrue();
        assertThat(disabled.shouldIncludeMeter(self))
                .as("with exclude-self disabled the panel reports BootUI's own meters too")
                .isTrue();
    }

    @Test
    void nullMeterIsNotSelf() {
        assertThat(filter.isBootUiMeter(null)).isFalse();
        assertThat(filter.shouldIncludeMeter(null)).isTrue();
    }
}
