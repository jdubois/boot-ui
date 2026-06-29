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
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus in-process span-capture pipeline end to end: a GenAI-style span emitted through the
 * real OpenTelemetry SDK is delivered to the BootUI {@code SpanProcessor} (registered by the extension's
 * OpenTelemetry-capability build step), normalized by the engine {@code BootUiSpanExporter}, stored in the
 * shared {@code TelemetryStore}, and surfaced on the Traces and AI Usage panels — with no OTLP receiver and
 * no network (see {@code application.properties}: {@code quarkus.otel.traces.exporter=none}).
 *
 * <p>This is the OpenTelemetry-<em>present</em> half of the telemetry coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the OTel-<em>absent</em> path (panels available,
 * answering empty, with the SDK never linked). The BootUI {@code SpanProcessor} is a
 * {@code SimpleSpanProcessor}, so a span is exported synchronously on {@link Span#end()} and is queryable
 * immediately after.</p>
 */
@QuarkusTest
class BootUiQuarkusTelemetryCaptureTest {

    @Inject
    OpenTelemetry openTelemetry;

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    /**
     * Emits a single OTel GenAI chat span (matching the {@code gen_ai.*} semantic conventions Spring AI and
     * LangChain4j produce) and returns its span id. The span carries no BootUI path, so it is not
     * self-filtered on capture.
     */
    private String emitChatSpan(String model, long inputTokens, long outputTokens) {
        Tracer tracer = openTelemetry.getTracer("io.github.jdubois.bootui.it");
        Span span = tracer.spanBuilder("chat " + model)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("gen_ai.operation.name", "chat")
                .setAttribute("gen_ai.system", "openai")
                .setAttribute("gen_ai.request.model", model)
                .setAttribute("gen_ai.response.model", model)
                .setAttribute("gen_ai.usage.input_tokens", inputTokens)
                .setAttribute("gen_ai.usage.output_tokens", outputTokens)
                .startSpan();
        String spanId = span.getSpanContext().getSpanId();
        span.end();
        return spanId;
    }

    @Test
    void capturedGenAiSpanSurfacesOnTracesAndAiOverview() {
        emitChatSpan("gpt-4o-mini", 12L, 8L);

        Response traces = probe().get("/bootui/api/traces");
        assertThat(traces.status()).as("GET /bootui/api/traces status").isEqualTo(200);
        assertThat(traces.json().path("retained").asInt(0))
                .as("the emitted span must be retained in the telemetry store")
                .isGreaterThanOrEqualTo(1);
        boolean anyAiTrace = false;
        for (JsonNode trace : traces.json().path("traces")) {
            if (trace.path("hasAi").asBoolean(false)) {
                anyAiTrace = true;
            }
        }
        assertThat(anyAiTrace)
                .as("the captured GenAI chat span must mark its trace as an AI trace")
                .isTrue();

        Response overview = probe().get("/bootui/api/ai/overview");
        assertThat(overview.status()).as("GET /bootui/api/ai/overview status").isEqualTo(200);
        assertThat(overview.json().path("enabled").asBoolean(false))
                .as("telemetry is enabled by default")
                .isTrue();
        assertThat(overview.json().path("totalChats").asInt(0))
                .as("the captured chat span must be counted")
                .isGreaterThanOrEqualTo(1);
        assertThat(overview.json().path("totalInputTokens").asLong(0))
                .as("the captured input-token usage must be aggregated")
                .isGreaterThanOrEqualTo(12L);
    }

    @Test
    void capturedChatAppearsInChatsAndChatDetail() {
        String spanId = emitChatSpan("claude-sonnet", 20L, 5L);

        Response chats = probe().get("/bootui/api/ai/chats");
        assertThat(chats.status()).as("GET /bootui/api/ai/chats status").isEqualTo(200);
        assertThat(chats.json().isArray()).as("chats is a JSON array").isTrue();

        JsonNode captured = null;
        for (JsonNode chat : chats.json()) {
            if (spanId.equals(chat.path("spanId").asText(null))) {
                captured = chat;
            }
        }
        assertThat(captured)
                .as("the emitted chat span (id %s) must appear in the chats list", spanId)
                .isNotNull();
        assertThat(captured.path("requestModel").asText(null))
                .as("the chat summary must carry the request model from the span")
                .isEqualTo("claude-sonnet");

        Response detail = probe().get("/bootui/api/ai/chats/" + spanId);
        assertThat(detail.status())
                .as("GET /bootui/api/ai/chats/{spanId} for a captured span must answer 200")
                .isEqualTo(200);
        assertThat(detail.json().path("summary").path("spanId").asText(null))
                .as("the detail must be for the requested span")
                .isEqualTo(spanId);
    }

    @Test
    void tokenSeriesReflectsCapturedUsage() {
        emitChatSpan("gpt-4o", 30L, 10L);

        Response tokens = probe().get("/bootui/api/ai/tokens");
        assertThat(tokens.status()).as("GET /bootui/api/ai/tokens status").isEqualTo(200);
        JsonNode buckets = tokens.json().path("buckets");
        assertThat(buckets.isArray()).as("the token series must be an array").isTrue();
        assertThat(buckets).as("the token series window must contain buckets").isNotEmpty();

        long seriesInputTokens = 0;
        for (JsonNode bucket : buckets) {
            seriesInputTokens += bucket.path("inputTokens").asLong(0);
        }
        assertThat(seriesInputTokens)
                .as("the captured chat's input tokens must land in the current-minute bucket")
                .isGreaterThanOrEqualTo(30L);
    }

    @Test
    void clearingTracesEmptiesTheBuffer() {
        emitChatSpan("gpt-4o-mini", 5L, 5L);

        Response before = probe().get("/bootui/api/traces");
        assertThat(before.json().path("retained").asInt(0))
                .as("a captured span must be retained before the buffer is cleared")
                .isGreaterThanOrEqualTo(1);

        Response cleared = probe().request("DELETE", "/bootui/api/traces", Map.of(), null);
        assertThat(cleared.status())
                .as("DELETE /bootui/api/traces (the first action-capable Quarkus panel) must answer 204")
                .isEqualTo(204);

        Response after = probe().get("/bootui/api/traces");
        assertThat(after.status())
                .as("GET /bootui/api/traces after clear status")
                .isEqualTo(200);
        assertThat(after.json().path("retained").asInt(-1))
                .as("clearing must drop every retained trace (the GET's own /bootui span is self-filtered)")
                .isZero();
    }

    @Test
    void selfBootUiSpansAreFilteredOnCapture() {
        Tracer tracer = openTelemetry.getTracer("io.github.jdubois.bootui.it");
        Span span = tracer.spanBuilder("GET /bootui/api/traces")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.route", "/bootui/api/traces")
                .startSpan();
        String selfTraceId = span.getSpanContext().getTraceId();
        span.end();

        Response traces = probe().get("/bootui/api/traces");
        assertThat(traces.status()).as("GET /bootui/api/traces status").isEqualTo(200);
        for (JsonNode trace : traces.json().path("traces")) {
            assertThat(trace.path("traceId").asText(""))
                    .as("a span carrying a /bootui path must be dropped on capture, not stored")
                    .isNotEqualTo(selfTraceId);
        }
    }
}
