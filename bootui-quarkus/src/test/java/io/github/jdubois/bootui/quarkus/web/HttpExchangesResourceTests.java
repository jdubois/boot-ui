package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Quarkus HTTP Exchanges resource reports the buffer's failure-preserving retention from the same snapshot as the
 * exchanges it lists, so the counts reconcile with the panel at the Spring adapters' parity.
 */
class HttpExchangesResourceTests {

    private static CapturedHttpExchange exchange(String path, int status, long durationMs) {
        return new CapturedHttpExchange(
                Instant.ofEpochMilli(1_000L),
                "GET",
                URI.create("http://localhost:8080" + path),
                status,
                durationMs,
                "127.0.0.1",
                null,
                null,
                Map.of(),
                Map.of(),
                null);
    }

    @Test
    void floodOfSuccessesKeepsTheRecentFailuresAndReportsReconcilingRetention() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(4, 50, 1_000L);
        buffer.record(exchange("/server-error", 500, 5));
        buffer.record(exchange("/slow", 200, 1_200));
        for (int i = 0; i < 25; i++) {
            buffer.record(exchange("/ok-" + i, 200, 5));
        }
        HttpExchangesResource resource = new HttpExchangesResource(
                buffer,
                new QuarkusExposurePolicy(new SmallRyeConfigBuilder()
                        .withSources(new PropertiesConfigSource(Map.of(), "test", 1000))
                        .build()),
                new SelfTelemetryClassifier(true, "/bootui", "/bootui/api"));

        HttpExchangesReport report = resource.exchanges(null, null, null, null, null);

        assertThat(report.exchanges())
                .extracting(HttpExchangeDto::path)
                .containsExactly("/ok-24", "/ok-23", "/slow", "/server-error");
        assertThat(report.retention()).isEqualTo(new CaptureRetentionDto(false, 4, 2, 4, 2, 23L, 1_000L));
        assertThat(report.retention().retained()).isEqualTo(report.recorded());
    }
}
