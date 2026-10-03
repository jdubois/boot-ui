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
 * One failed generative-AI chat span whose prompt, completion, input messages, sensitive attribute, and exception event
 * each carry a secret, and the checks every adapter's AI Framework chat detail ({@code GET /ai/chats/{spanId}}) must pass
 * for it. Spans are stored raw and masked when read, so each check runs under a live exposure change made through
 * {@link LogTailExposureContract#withExposure}, without a restart.
 */
final class AiChatExposureContract {

    private static final String MASK = "******";

    private final String traceId = UUID.randomUUID().toString().replace("-", "");

    final String spanId = traceId.substring(0, 16);

    private final String marker = "ai-chat-exposure-" + UUID.randomUUID();

    private final String promptKey = "pk-" + UUID.randomUUID();

    private final String completionPassword = "pw-" + UUID.randomUUID();

    private final String messageToken = "mt-" + UUID.randomUUID();

    private final String attributeKey = "ak-" + UUID.randomUUID();

    private final String errorSecret = "es-" + UUID.randomUUID();

    /** The chat span as an exporter would record it, before any exposure rule. */
    NormalizedSpan span() {
        long start = System.currentTimeMillis() * 1_000_000L;
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("gen_ai.operation.name", AttributeValue.ofString("chat"));
        attributes.put("gen_ai.system", AttributeValue.ofString("conformance"));
        attributes.put("gen_ai.request.model", AttributeValue.ofString("conformance-model"));
        attributes.put("gen_ai.usage.input_tokens", AttributeValue.ofNumber(42L));
        attributes.put("gen_ai.prompt", AttributeValue.ofString(prompt()));
        attributes.put("gen_ai.completion", AttributeValue.ofString(completion()));
        attributes.put(
                "gen_ai.input.messages",
                AttributeValue.ofString(
                        "[{\"role\":\"user\",\"content\":\"" + marker + " token=" + messageToken + "\"}]"));
        attributes.put("app.api_key", AttributeValue.ofString(attributeKey));
        Map<String, AttributeValue> exception = new LinkedHashMap<>();
        exception.put("exception.type", AttributeValue.ofString("java.lang.IllegalStateException"));
        exception.put("exception.message", AttributeValue.ofString(errorMessage()));
        exception.put(
                "exception.stacktrace",
                AttributeValue.ofString("java.lang.IllegalStateException: " + errorMessage()
                        + "\n\tat com.example.Conformance.chat(Conformance.java:1)"));
        return new NormalizedSpan(
                traceId,
                spanId,
                null,
                "chat conformance-model",
                "CLIENT",
                "conformance",
                "conformance",
                start,
                start + 1_000_000L,
                "ERROR",
                errorMessage(),
                attributes,
                List.of(new NormalizedEvent("exception", 500_000L, exception)));
    }

    void assertVerbatim(JsonNode detail, String surface) {
        assertThat(attribute(detail, "gen_ai.prompt", surface).asText())
                .as("%s gen_ai.prompt", surface)
                .isEqualTo(prompt());
        assertThat(attribute(detail, "app.api_key", surface).asText())
                .as("%s app.api_key", surface)
                .isEqualTo(attributeKey);
        assertThat(exceptionAttribute(detail, "exception.message", surface).asText())
                .as("%s exception.message", surface)
                .isEqualTo(errorMessage());
    }

    void assertMasked(JsonNode detail, String surface) {
        assertNoSecret(detail, surface);
        assertThat(attribute(detail, "gen_ai.prompt", surface).asText())
                .as("%s gen_ai.prompt", surface)
                .isEqualTo(marker + " uses apiKey=" + MASK);
        assertThat(attribute(detail, "gen_ai.completion", surface).asText())
                .as("%s gen_ai.completion", surface)
                .isEqualTo(marker + " accepted password=" + MASK);
        assertThat(attribute(detail, "app.api_key", surface).asText())
                .as("%s app.api_key", surface)
                .isEqualTo(MASK);
        assertThat(exceptionAttribute(detail, "exception.message", surface).asText())
                .as("%s exception.message", surface)
                .isEqualTo(marker + " failed with secret=" + MASK);
        assertMetadata(detail, surface);
    }

    void assertOmitted(JsonNode detail, String surface) {
        assertNoSecret(detail, surface);
        assertThat(detail.toString())
                .as("%s must carry no prompt, completion, or message text", surface)
                .doesNotContain(marker);
        for (String key : List.of("gen_ai.prompt", "gen_ai.completion", "gen_ai.input.messages")) {
            assertThat(attribute(detail, key, surface).isNull())
                    .as("%s %s", surface, key)
                    .isTrue();
        }
        assertThat(exceptionAttribute(detail, "exception.message", surface).isNull())
                .as("%s exception.message", surface)
                .isTrue();
        assertThat(exceptionAttribute(detail, "exception.stacktrace", surface).isNull())
                .as("%s exception.stacktrace", surface)
                .isTrue();
        assertMetadata(detail, surface);
    }

    /** Asserts that a whole response, whatever its shape, carries none of the span's secrets. */
    void assertNoSecret(JsonNode body, String surface) {
        assertThat(body.toString())
                .as("%s must never carry a secret value", surface)
                .doesNotContain(promptKey, completionPassword, messageToken, attributeKey, errorSecret);
    }

    private void assertMetadata(JsonNode detail, String surface) {
        JsonNode summary = detail.path("summary");
        assertThat(summary.path("spanId").asText())
                .as("%s summary.spanId", surface)
                .isEqualTo(spanId);
        assertThat(summary.path("requestModel").asText())
                .as("%s summary.requestModel", surface)
                .isEqualTo("conformance-model");
        assertThat(summary.path("inputTokens").asLong())
                .as("%s summary.inputTokens", surface)
                .isEqualTo(42L);
        assertThat(detail.path("contentCaptured").asBoolean())
                .as("%s contentCaptured", surface)
                .isTrue();
        assertThat(attribute(detail, "gen_ai.operation.name", surface).asText())
                .as("%s gen_ai.operation.name", surface)
                .isEqualTo("chat");
        assertThat(exceptionAttribute(detail, "exception.type", surface).asText())
                .as("%s exception.type", surface)
                .isEqualTo("java.lang.IllegalStateException");
    }

    private static JsonNode exceptionAttribute(JsonNode detail, String key, String surface) {
        for (JsonNode event : detail.path("events")) {
            if ("exception".equals(event.path("name").asText())) {
                return value(event.path("attributes"), key, surface);
            }
        }
        throw new AssertionError(surface + " must keep the exception event; received " + detail);
    }

    private static JsonNode attribute(JsonNode detail, String key, String surface) {
        return value(detail.path("attributes"), key, surface);
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

    private String prompt() {
        return marker + " uses apiKey=" + promptKey;
    }

    private String completion() {
        return marker + " accepted password=" + completionPassword;
    }

    private String errorMessage() {
        return marker + " failed with secret=" + errorSecret;
    }
}
