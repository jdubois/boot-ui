package io.github.jdubois.bootui.engine.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.GrpcCallMetricSample;
import io.github.jdubois.bootui.spi.GrpcCallSide;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MicrometerGrpcMetricsProvider}. The panel's central promise is that BootUI reports
 * only the gRPC metrics an application already publishes and never installs its own interceptor to create
 * them, so these tests pin both supported meter conventions, the "no registry" and "no series" answers, and
 * the deliberate {@code count = 0} on in-progress samples that keeps call totals from doubling.
 */
class MicrometerGrpcMetricsProviderTests {

    @Test
    void reportsUnavailableWithoutARegistry() {
        MicrometerGrpcMetricsProvider provider = new MicrometerGrpcMetricsProvider(() -> null);

        assertThat(provider.available()).isFalse();
        assertThat(provider.unavailableReason()).isNotBlank();
        assertThat(provider.samples()).isEmpty();
    }

    @Test
    void survivesARegistrySupplierThatFails() {
        MicrometerGrpcMetricsProvider provider = new MicrometerGrpcMetricsProvider(() -> {
            throw new IllegalStateException("no registry yet");
        });

        assertThat(provider.available()).isFalse();
        assertThat(provider.samples()).isEmpty();
    }

    @Test
    void reportsNoSamplesWhenTheApplicationPublishesNoGrpcMeters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.timer("http.server.requests", "uri", "/api").record(Duration.ofMillis(5));

        MicrometerGrpcMetricsProvider provider = new MicrometerGrpcMetricsProvider(() -> registry);

        // A registry that publishes no gRPC series is honestly reported as unavailable rather than as "zero calls".
        assertThat(provider.available()).isFalse();
        assertThat(provider.unavailableReason()).isNotBlank();
        assertThat(provider.samples()).isEmpty();
    }

    @Test
    void readsTheInterceptorConventionUsedByTheQuarkusMicrometerBinder() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer timer = Timer.builder("grpc.server.processing.duration")
                .tag("service", "example.Greeter")
                .tag("method", "SayHello")
                .tag("methodType", "UNARY")
                .tag("statusCode", "OK")
                .register(registry);
        timer.record(Duration.ofMillis(10));
        timer.record(Duration.ofMillis(30));

        List<GrpcCallMetricSample> samples = new MicrometerGrpcMetricsProvider(() -> registry).samples();

        assertThat(samples).hasSize(1);
        GrpcCallMetricSample sample = samples.get(0);
        assertThat(sample.side()).isEqualTo(GrpcCallSide.SERVER);
        assertThat(sample.service()).isEqualTo("example.Greeter");
        assertThat(sample.method()).isEqualTo("SayHello");
        assertThat(sample.status()).isEqualTo("OK");
        assertThat(sample.count()).isEqualTo(2L);
        assertThat(sample.totalDurationMs()).isEqualTo(40d);
        assertThat(sample.maxDurationMs()).isEqualTo(30d);
        assertThat(sample.activeCalls()).isNull();
    }

    @Test
    void readsTheObservationConventionUsedBySpringGrpc() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer.builder("grpc.client")
                .tag("rpc.service", "billing.Ledger")
                .tag("rpc.method", "Charge")
                .tag("grpc.status_code", "OK")
                .register(registry)
                .record(Duration.ofMillis(20));
        LongTaskTimer active = LongTaskTimer.builder("grpc.server.active")
                .tag("rpc.service", "example.Greeter")
                .tag("rpc.method", "Chat")
                .register(registry);
        LongTaskTimer.Sample inFlight = active.start();

        try {
            List<GrpcCallMetricSample> samples = new MicrometerGrpcMetricsProvider(() -> registry).samples();

            assertThat(samples)
                    .extracting("side", "service", "method", "count", "activeCalls")
                    .containsExactlyInAnyOrder(
                            org.assertj.core.api.Assertions.tuple(
                                    GrpcCallSide.CLIENT, "billing.Ledger", "Charge", 1L, null),
                            org.assertj.core.api.Assertions.tuple(
                                    GrpcCallSide.SERVER, "example.Greeter", "Chat", 0L, 1L));
        } finally {
            inFlight.stop();
        }
    }

    @Test
    void ignoresMetersOfTheRightNameButTheWrongType() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("grpc.server.requests.sent", "service", "example.Greeter");

        assertThat(new MicrometerGrpcMetricsProvider(() -> registry).samples()).isEmpty();
    }
}
