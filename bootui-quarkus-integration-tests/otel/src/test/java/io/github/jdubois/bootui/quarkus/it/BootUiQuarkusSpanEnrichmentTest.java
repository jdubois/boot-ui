package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Proves the BootUI OpenTelemetry <em>enrichment</em> path end to end on Quarkus: the identity
 * {@code SpanProcessor} stamps {@code bootui.enriched}/service on every span at start, and the capture-time
 * {@code OtelSpanEnricher} — installed onto the shared {@code ExceptionStore} by the extension's
 * OpenTelemetry-capability wiring — stamps {@code bootui.exception.*} onto the active server span as a
 * failure is captured, so a cross-service trace waterfall carries BootUI depth per service.
 */
@QuarkusTest
class BootUiQuarkusSpanEnrichmentTest {

    @Inject
    OpenTelemetry openTelemetry;

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void identitySpanProcessorStampsEnrichedMarkerOnEmittedSpans() {
        Tracer tracer = openTelemetry.getTracer("io.github.jdubois.bootui.it");
        Span span = tracer.spanBuilder("enrichment-probe")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        String traceId = span.getSpanContext().getTraceId();
        span.end();

        Response detail = probe().get("/bootui/api/traces/" + traceId);
        assertThat(detail.status())
                .as("GET /bootui/api/traces/{traceId} for the emitted span must answer 200")
                .isEqualTo(200);

        boolean enriched = false;
        for (JsonNode spanNode : detail.json().path("spans")) {
            for (JsonNode attr : spanNode.path("attributes")) {
                if ("bootui.enriched".equals(attr.path("key").asText())) {
                    enriched = attr.path("value").asBoolean(false);
                }
            }
        }
        assertThat(enriched)
                .as("the identity SpanProcessor must stamp bootui.enriched=true on every emitted span")
                .isTrue();
    }

    @Test
    void exceptionCaptureStampsBootUiDepthOnTheActiveServerSpan() {
        Response probeCall = probe().get("/it/boom");
        assertThat(probeCall.status())
                .as("the boom probe endpoint must fail with a server error")
                .isGreaterThanOrEqualTo(500);

        Response traces = probe().get("/bootui/api/traces");
        assertThat(traces.status()).as("GET /bootui/api/traces status").isEqualTo(200);

        String boomTraceId = null;
        for (JsonNode trace : traces.json().path("traces")) {
            if ("/it/boom".equals(trace.path("httpPath").asText(null))) {
                boomTraceId = trace.path("traceId").asText(null);
            }
        }
        assertThat(boomTraceId)
                .as("the /it/boom request must surface as a captured server trace")
                .isNotNull();

        Response detail = probe().get("/bootui/api/traces/" + boomTraceId);
        assertThat(detail.status()).as("GET /bootui/api/traces/{traceId} status").isEqualTo(200);

        String exceptionType = null;
        long exceptionCount = 0;
        for (JsonNode spanNode : detail.json().path("spans")) {
            for (JsonNode attr : spanNode.path("attributes")) {
                String key = attr.path("key").asText();
                if ("bootui.exception.type".equals(key)) {
                    exceptionType = attr.path("value").asText(null);
                } else if ("bootui.exceptions".equals(key)) {
                    exceptionCount = attr.path("value").asLong(0);
                }
            }
        }
        assertThat(exceptionType)
                .as("the enricher must stamp bootui.exception.type on the active server span as the "
                        + "failure is captured")
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(exceptionCount)
                .as("the enricher must increment bootui.exceptions on the active server span")
                .isGreaterThanOrEqualTo(1L);
    }
}
