package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.HttpHeaderDto;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpExchangesServiceTests {

    private final HttpExchangesService service = new HttpExchangesService();

    private CapturedHttpExchange exchange(String path, int status, Map<String, List<String>> requestHeaders) {
        return new CapturedHttpExchange(
                Instant.parse("2024-01-01T00:00:00Z"),
                "GET",
                URI.create("http://localhost:8080" + path),
                status,
                12L,
                "127.0.0.1",
                "alice",
                "S1",
                requestHeaders,
                Map.of("Content-Length", List.of("42")),
                null);
    }

    private CapturedHttpExchange exchangeWithTrace(String capturedTraceId, Map<String, List<String>> requestHeaders) {
        return new CapturedHttpExchange(
                Instant.parse("2024-01-01T00:00:00Z"),
                "GET",
                URI.create("http://localhost:8080/api"),
                200,
                12L,
                "127.0.0.1",
                "alice",
                "S1",
                requestHeaders,
                Map.of("Content-Length", List.of("42")),
                capturedTraceId);
    }

    @Test
    void prefersCapturedTraceIdOverInboundHeaders() {
        HttpExchangesReport report = service.report(
                List.of(exchangeWithTrace("span-trace-id", Map.of("x-b3-traceid", List.of("header-trace-id")))),
                uri -> false,
                true,
                ValueExposure.MASKED,
                null,
                null,
                null,
                null,
                null);
        assertThat(report.exchanges().get(0).traceId()).isEqualTo("span-trace-id");
    }

    @Test
    void fallsBackToHeaderTraceIdWhenNoneCaptured() {
        HttpExchangesReport report = service.report(
                List.of(exchangeWithTrace(null, Map.of("x-b3-traceid", List.of("header-trace-id")))),
                uri -> false,
                true,
                ValueExposure.MASKED,
                null,
                null,
                null,
                null,
                null);
        assertThat(report.exchanges().get(0).traceId()).isEqualTo("header-trace-id");
    }

    @Test
    void masksSensitiveHeadersAndDerivesStatusFamily() {
        HttpExchangesReport report = service.report(
                List.of(exchange("/api?token=abc", 503, Map.of("Authorization", List.of("Bearer x")))),
                uri -> false,
                true,
                ValueExposure.MASKED,
                null,
                null,
                null,
                null,
                null);
        HttpExchangeDto dto = report.exchanges().get(0);
        assertThat(dto.statusFamily()).isEqualTo("5xx");
        assertThat(dto.responseSizeBytes()).isEqualTo(42L);
        HttpHeaderDto auth = dto.requestHeaders().stream()
                .filter(h -> h.name().equalsIgnoreCase("authorization"))
                .findFirst()
                .orElseThrow();
        assertThat(auth.masked()).isTrue();
        assertThat(auth.values()).containsExactly("******");
        assertThat(dto.query()).contains("token=******");
    }

    @Test
    void excludesSelfTrafficAndCounts() {
        HttpExchangesReport report = service.report(
                List.of(exchange("/api/x", 200, Map.of()), exchange("/bootui/api/x", 200, Map.of())),
                uri -> uri.contains("/bootui"),
                true,
                ValueExposure.MASKED,
                null,
                null,
                null,
                null,
                null);
        assertThat(report.total()).isEqualTo(1);
        assertThat(report.hiddenSelf()).isEqualTo(1);
    }

    @Test
    void bufferCapsAndReversesNewestFirst() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(2);
        buffer.record(exchange("/a", 200, Map.of()));
        buffer.record(exchange("/b", 200, Map.of()));
        buffer.record(exchange("/c", 200, Map.of()));
        List<CapturedHttpExchange> snapshot = buffer.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).uri().getPath()).isEqualTo("/c");
        buffer.suspendForIdle();
        assertThat(buffer.snapshot()).isEmpty();
    }
}
