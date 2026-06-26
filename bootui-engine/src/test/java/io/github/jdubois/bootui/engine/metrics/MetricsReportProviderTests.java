package io.github.jdubois.bootui.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.MetricDetailDto;
import io.github.jdubois.bootui.core.dto.MetricMeasurementDto;
import io.github.jdubois.bootui.core.dto.MetricMeterDto;
import io.github.jdubois.bootui.core.dto.MetricsReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MetricsReportProviderTests {

    private static MetricsReportProvider provider(MeterRegistry registry) {
        return provider(registry, meter -> true);
    }

    private static MetricsReportProvider provider(MeterRegistry registry, Predicate<Meter> filter) {
        Supplier<MeterRegistry> supplier = () -> registry;
        return new MetricsReportProvider(supplier, filter);
    }

    @Test
    void metricsGroupsMetersByNameAndAggregatesTagValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("bootui.sample.requests")
                .description("Sample requests")
                .baseUnit("requests")
                .tag("outcome", "success")
                .register(registry)
                .increment(2);
        Counter.builder("bootui.sample.requests")
                .description("Sample requests")
                .baseUnit("requests")
                .tag("outcome", "failure")
                .register(registry)
                .increment();

        MetricsReport report = provider(registry).metrics();

        assertThat(report.metricsAvailable()).isTrue();
        assertThat(report.total()).isGreaterThan(0);
        MetricMeterDto meter = report.meters().stream()
                .filter(m -> m.name().equals("bootui.sample.requests"))
                .findFirst()
                .orElseThrow();
        assertThat(meter.description()).isEqualTo("Sample requests");
        assertThat(meter.baseUnit()).isEqualTo("requests");
        assertThat(meter.type()).isEqualTo("COUNTER");
        assertThat(meter.availableTags()).hasSize(1);
        assertThat(meter.availableTags().get(0).key()).isEqualTo("outcome");
        assertThat(meter.availableTags().get(0).values()).containsExactly("failure", "success");
    }

    @Test
    void detailFiltersByTagsAndAggregatesFiniteMeasurements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("bootui.sample.requests")
                .tag("outcome", "success")
                .register(registry)
                .increment(2);
        Counter.builder("bootui.sample.requests")
                .tag("outcome", "failure")
                .register(registry)
                .increment(3);

        MetricDetailDto detail = provider(registry).metric("bootui.sample.requests", List.of("outcome:success"));

        assertThat(detail.metricsAvailable()).isTrue();
        assertThat(detail.name()).isEqualTo("bootui.sample.requests");
        assertThat(detail.measurements()).contains(new MetricMeasurementDto("count", 2.0));
        assertThat(detail.samples()).hasSize(1);
        assertThat(detail.samples().get(0).tags().get(0).key()).isEqualTo("outcome");
        assertThat(detail.samples().get(0).tags().get(0).value()).isEqualTo("success");
    }

    @Test
    void detailUsesMaximumWhenAggregatingMaxStatistic() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer.builder("bootui.sample.latency")
                .tag("node", "one")
                .register(registry)
                .record(10, TimeUnit.MILLISECONDS);
        Timer.builder("bootui.sample.latency")
                .tag("node", "two")
                .register(registry)
                .record(25, TimeUnit.MILLISECONDS);

        MetricDetailDto detail = provider(registry).metric("bootui.sample.latency", null);

        assertThat(detail.measurements())
                .filteredOn(measurement -> measurement.statistic().equals("max"))
                .extracting(MetricMeasurementDto::value)
                .containsExactly(0.025);
    }

    @Test
    void detailAcceptsColonInTagValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger value = new AtomicInteger(7);
        Gauge.builder("bootui.sample.gauge", value, AtomicInteger::get)
                .tags(Tags.of("uri", "http://localhost:8080/api"))
                .register(registry);

        MetricDetailDto detail =
                provider(registry).metric("bootui.sample.gauge", List.of("uri:http://localhost:8080/api"));

        assertThat(detail.samples()).hasSize(1);
        assertThat(detail.measurements().get(0).value()).isEqualTo(7.0);
    }

    @Test
    void appliesMeterVisibilityPredicate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("http.server.requests")
                .tag("uri", "/bootui/api/beans")
                .register(registry)
                .increment(5);
        Counter.builder("http.server.requests")
                .tag("uri", "/api/orders")
                .register(registry)
                .increment(2);

        Predicate<Meter> hideBootUi = meter -> {
            String uri = meter.getId().getTag("uri");
            return uri == null || !uri.startsWith("/bootui");
        };

        MetricDetailDto detail = provider(registry, hideBootUi).metric("http.server.requests", null);

        assertThat(detail.samples()).hasSize(1);
        assertThat(detail.samples().get(0).tags().get(0).value()).isEqualTo("/api/orders");
        assertThat(detail.availableTags().get(0).values()).containsExactly("/api/orders");
    }

    @Test
    void detailRejectsMalformedTagFilters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("bootui.sample.requests").increment();

        assertThatThrownBy(() -> provider(registry).metric("bootui.sample.requests", List.of("malformed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key:value");
    }

    @Test
    void metricsReportUnavailableWhenNoRegistry() {
        MetricsReport report = provider(null).metrics();

        assertThat(report.metricsAvailable()).isFalse();
        assertThat(report.total()).isZero();
        assertThat(report.meters()).isEmpty();
    }

    @Test
    void detailUnavailableWhenNoRegistry() {
        MetricDetailDto detail = provider(null).metric("anything", null);

        assertThat(detail.metricsAvailable()).isFalse();
        assertThat(detail.name()).isEqualTo("anything");
        assertThat(detail.samples()).isEmpty();
    }
}
