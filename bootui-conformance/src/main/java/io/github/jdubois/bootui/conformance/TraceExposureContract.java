package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.engine.telemetry.AttributeValue;
import io.github.jdubois.bootui.engine.telemetry.NormalizedEvent;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One failed span whose status message, exception event, URL, and authorization header each carry a secret, and the
 * checks every adapter's trace surfaces must pass for it: {@code GET /traces/{id}} and the per-request profile that
 * embeds the trace. Spans are stored raw and masked when read, so each check runs under a live exposure change made
 * through {@link LogTailExposureContract#withExposure}, without a restart.
 */
final class TraceExposureContract {

    private static final String MASK = "******";

    final String traceId = UUID.randomUUID().toString().replace("-", "");

    private final String spanId = traceId.substring(0, 16);

    private final String marker = "trace-exposure-" + UUID.randomUUID();

    private final String apiToken = "tok-" + UUID.randomUUID();

    private final String accessToken = "at-" + UUID.randomUUID();

    private final String bearer = "bearer" + UUID.randomUUID().toString().replace("-", "");

    /** The span as an exporter would record it, before any exposure rule. */
    NormalizedSpan span() {
        long start = System.currentTimeMillis() * 1_000_000L;
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("url.path", AttributeValue.ofString("/conformance-trace-exposure"));
        attributes.put(
                "url.full",
                AttributeValue.ofString(
                        "http://localhost/conformance-trace-exposure?access_token=" + accessToken + "&page=1"));
        attributes.put("http.request.header.authorization", AttributeValue.ofList(List.of("Bearer " + bearer)));
        Map<String, AttributeValue> exception = new LinkedHashMap<>();
        exception.put("exception.type", AttributeValue.ofString("java.lang.IllegalStateException"));
        exception.put("exception.message", AttributeValue.ofString(message()));
        exception.put(
                "exception.stacktrace",
                AttributeValue.ofString("java.lang.IllegalStateException: " + message()
                        + "\n\tat com.example.Conformance.run(Conformance.java:1)"));
        return new NormalizedSpan(
                traceId,
                spanId,
                null,
                "GET /conformance-trace-exposure",
                "SERVER",
                "conformance",
                "conformance",
                start,
                start + 1_000_000L,
                "ERROR",
                message(),
                attributes,
                List.of(new NormalizedEvent("exception", 500_000L, exception)));
    }

    /** A W3C {@code traceparent} that puts a request on this span's trace. */
    String traceparent() {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    void assertVerbatim(JsonNode trace, String surface) {
        JsonNode span = span(trace, surface);
        assertThat(span.path("statusMessage").asText())
                .as("%s statusMessage", surface)
                .isEqualTo(message());
        assertThat(exceptionAttribute(span, "exception.message", surface).asText())
                .as("%s exception.message", surface)
                .isEqualTo(message());
    }

    void assertMasked(JsonNode trace, String surface) {
        assertNoSecret(trace, surface);
        JsonNode span = span(trace, surface);
        String masked = marker + " failed with apiToken=" + MASK;
        assertThat(span.path("statusMessage").asText())
                .as("%s statusMessage", surface)
                .isEqualTo(masked);
        assertThat(exceptionAttribute(span, "exception.message", surface).asText())
                .as("%s exception.message", surface)
                .isEqualTo(masked);
        assertThat(exceptionAttribute(span, "exception.stacktrace", surface).asText())
                .as("%s exception.stacktrace", surface)
                .startsWith("java.lang.IllegalStateException: " + masked)
                .contains("Conformance.run(Conformance.java:1)");
        assertThat(attribute(span, "url.full", surface).asText())
                .as("%s url.full", surface)
                .isEqualTo("http://localhost/conformance-trace-exposure?access_token=" + MASK + "&page=1");
        assertThat(attribute(span, "http.request.header.authorization", surface)
                        .path(0)
                        .asText())
                .as("%s http.request.header.authorization", surface)
                .isEqualTo(MASK);
        assertMetadata(span, surface);
    }

    void assertOmitted(JsonNode trace, String surface) {
        assertNoSecret(trace, surface);
        assertThat(trace.toString())
                .as("%s must carry no message text", surface)
                .doesNotContain(marker);
        JsonNode span = span(trace, surface);
        assertThat(span.has("statusMessage"))
                .as("%s keeps the statusMessage field", surface)
                .isTrue();
        assertThat(span.path("statusMessage").isNull())
                .as("%s statusMessage", surface)
                .isTrue();
        assertThat(exceptionAttribute(span, "exception.message", surface).isNull())
                .as("%s exception.message", surface)
                .isTrue();
        assertThat(exceptionAttribute(span, "exception.stacktrace", surface).isNull())
                .as("%s exception.stacktrace", surface)
                .isTrue();
        assertThat(attribute(span, "http.request.header.authorization", surface).isNull())
                .as("%s http.request.header.authorization", surface)
                .isTrue();
        assertMetadata(span, surface);
    }

    /** Asserts that a whole response, whatever its shape, carries none of the span's secrets. */
    void assertNoSecret(JsonNode body, String surface) {
        assertThat(body.toString())
                .as("%s must never carry a secret value", surface)
                .doesNotContain(apiToken, accessToken, bearer);
    }

    private void assertMetadata(JsonNode span, String surface) {
        assertThat(span.path("statusCode").asText())
                .as("%s statusCode", surface)
                .isEqualTo("ERROR");
        assertThat(span.path("name").asText()).as("%s name", surface).isEqualTo("GET /conformance-trace-exposure");
        assertThat(exceptionAttribute(span, "exception.type", surface).asText())
                .as("%s exception.type", surface)
                .isEqualTo("java.lang.IllegalStateException");
    }

    private JsonNode span(JsonNode trace, String surface) {
        assertThat(trace.path("traceId").asText()).as("%s traceId", surface).isEqualTo(traceId);
        for (JsonNode span : trace.path("spans")) {
            if (spanId.equals(span.path("spanId").asText())) {
                return span;
            }
        }
        throw new AssertionError(surface + " must return the seeded span; received " + trace);
    }

    private static JsonNode exceptionAttribute(JsonNode span, String key, String surface) {
        for (JsonNode event : span.path("events")) {
            if ("exception".equals(event.path("name").asText())) {
                return value(event.path("attributes"), key, surface);
            }
        }
        throw new AssertionError(surface + " must keep the exception event; received " + span);
    }

    private static JsonNode attribute(JsonNode span, String key, String surface) {
        return value(span.path("attributes"), key, surface);
    }

    private static JsonNode value(JsonNode attributes, String key, String surface) {
        for (JsonNode attribute : attributes) {
            if (key.equals(attribute.path("key").asText())) {
                assertThat(attribute.has("value"))
                        .as("%s keeps the %s value field", surface, key)
                        .isTrue();
                return attribute.path("value");
            }
        }
        throw new AssertionError(surface + " must keep the " + key + " attribute; received " + attributes);
    }

    private String message() {
        return marker + " failed with apiToken=" + apiToken;
    }
}
