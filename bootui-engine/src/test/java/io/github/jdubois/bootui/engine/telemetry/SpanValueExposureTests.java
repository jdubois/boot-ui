package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SpanAttributeDto;
import io.github.jdubois.bootui.core.dto.SpanEventDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanValueExposureTests {

    private static final String MASK = SecretMasker.MASKED_VALUE;

    private static final SpanValueExposure FULL = rule(ValueExposure.FULL, true);

    private static final SpanValueExposure MASKED = rule(ValueExposure.MASKED, true);

    private static final SpanValueExposure UNMASKED = rule(ValueExposure.MASKED, false);

    private static final SpanValueExposure METADATA_ONLY = rule(ValueExposure.METADATA_ONLY, true);

    @Test
    void statusMessageFollowsTheMessageExposureRule() {
        String message = "Boom: apiToken=secret-1 Authorization: Bearer abcdefghijklmnop0123";

        assertThat(FULL.statusMessage(message)).isEqualTo(message);
        assertThat(UNMASKED.statusMessage(message)).isEqualTo(message);
        assertThat(MASKED.statusMessage(message))
                .isEqualTo("Boom: apiToken=" + MASK + " Authorization: Bearer " + MASK);
        assertThat(METADATA_ONLY.statusMessage(message)).isNull();
        assertThat(MASKED.statusMessage(null)).isNull();
    }

    @Test
    void exceptionEventTextIsMaskedOrOmittedButItsTypeAndNameStay() {
        List<NormalizedEvent> events = List.of(new NormalizedEvent(
                "exception",
                7L,
                ordered(
                        "exception.type", AttributeValue.ofString("java.lang.IllegalStateException"),
                        "exception.message", AttributeValue.ofString("password=hunter2 failed"),
                        "exception.stacktrace",
                                AttributeValue.ofString("java.lang.IllegalStateException: password=hunter2 failed\n"
                                        + "\tat com.example.Sample.run(Sample.java:1)"))));

        SpanEventDto masked = MASKED.events(events).get(0);
        assertThat(masked.name()).isEqualTo("exception");
        assertThat(masked.timeOffsetNanos()).isEqualTo(7L);
        assertThat(value(masked.attributes(), "exception.type")).isEqualTo("java.lang.IllegalStateException");
        assertThat(value(masked.attributes(), "exception.message")).isEqualTo("password=" + MASK + " failed");
        assertThat((String) value(masked.attributes(), "exception.stacktrace"))
                .doesNotContain("hunter2")
                .contains("Sample.run(Sample.java:1)");

        SpanEventDto omitted = METADATA_ONLY.events(events).get(0);
        assertThat(value(omitted.attributes(), "exception.type")).isEqualTo("java.lang.IllegalStateException");
        assertThat(value(omitted.attributes(), "exception.message")).isNull();
        assertThat(value(omitted.attributes(), "exception.stacktrace")).isNull();
        assertThat(omitted.attributes())
                .extracting(SpanAttributeDto::key, SpanAttributeDto::type)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("exception.message", "string"),
                        org.assertj.core.groups.Tuple.tuple("exception.stacktrace", "string"));

        SpanEventDto full = FULL.events(events).get(0);
        assertThat(value(full.attributes(), "exception.message")).isEqualTo("password=hunter2 failed");
    }

    @Test
    void urlAttributesFollowTheUriMaskingRule() {
        Map<String, AttributeValue> attributes = ordered(
                "url.full", AttributeValue.ofString("https://user:pw@api.example.com/v1?api_key=k-1&page=2"),
                "url.query", AttributeValue.ofString("token=t-1&page=2"),
                "http.target", AttributeValue.ofString("/orders?access_token=t-2"),
                "url.path", AttributeValue.ofString("/cart;token=s-1"));

        List<SpanAttributeDto> masked = MASKED.attributes(attributes);
        assertThat(value(masked, "url.full"))
                .isEqualTo("https://" + MASK + "@api.example.com/v1?api_key=" + MASK + "&page=2");
        assertThat(value(masked, "url.query")).isEqualTo("token=" + MASK + "&page=2");
        assertThat(value(masked, "http.target")).isEqualTo("/orders?access_token=" + MASK);
        assertThat(value(masked, "url.path")).isEqualTo("/cart;token=" + MASK);

        List<SpanAttributeDto> metadata = METADATA_ONLY.attributes(attributes);
        assertThat(value(metadata, "url.full")).isEqualTo("https://" + MASK + "@api.example.com/v1");
        assertThat(value(metadata, "url.query")).isNull();
        assertThat(value(metadata, "http.target")).isEqualTo("/orders");

        List<SpanAttributeDto> full = FULL.attributes(attributes);
        assertThat(value(full, "url.query")).isEqualTo("token=t-1&page=2");
        assertThat(value(full, "url.full"))
                .as("user-info is never a value BootUI shows, as in the HTTP Exchanges panel")
                .isEqualTo("https://" + MASK + "@api.example.com/v1?api_key=k-1&page=2");
    }

    @Test
    void headerAndBoundParameterValuesAreMaskedOrOmitted() {
        Map<String, AttributeValue> attributes = ordered(
                "http.request.header.authorization", AttributeValue.ofList(List.of("Bearer abc")),
                "http.request.header.accept", AttributeValue.ofList(List.of("application/json")),
                "http.response.header.set-cookie", AttributeValue.ofString("SESSION=s-2"),
                "db.query.parameter.0", AttributeValue.ofString("alice@example.com"));

        List<SpanAttributeDto> masked = MASKED.attributes(attributes);
        assertThat(value(masked, "http.request.header.authorization")).isEqualTo(List.of(MASK));
        assertThat(value(masked, "http.request.header.accept")).isEqualTo(List.of("application/json"));
        assertThat(value(masked, "http.response.header.set-cookie")).isEqualTo(MASK);
        assertThat(value(masked, "db.query.parameter.0")).isEqualTo("alice@example.com");

        List<SpanAttributeDto> metadata = METADATA_ONLY.attributes(attributes);
        assertThat(metadata).extracting(SpanAttributeDto::value).containsOnlyNulls();
        assertThat(metadata).extracting(SpanAttributeDto::type).containsExactly("list", "list", "string", "string");

        assertThat(value(FULL.attributes(attributes), "http.request.header.authorization"))
                .isEqualTo(List.of("Bearer abc"));
        assertThat(value(UNMASKED.attributes(attributes), "http.request.header.authorization"))
                .isEqualTo(List.of("Bearer abc"));
    }

    @Test
    void otherStringsAreMaskedBySensitiveNameOrScrubbedButNumbersStay() {
        Map<String, AttributeValue> attributes = ordered(
                "app.api_key", AttributeValue.ofString("k-3"),
                "db.statement", AttributeValue.ofString("update users set password='p-4' where id=?"),
                "http.route", AttributeValue.ofString("/api/sample/{id}"),
                "gen_ai.usage.input_tokens", AttributeValue.ofNumber(42),
                "bootui.exception.type", AttributeValue.ofString("java.lang.IllegalStateException"),
                "error", AttributeValue.ofBoolean(true));

        List<SpanAttributeDto> masked = MASKED.attributes(attributes);
        assertThat(value(masked, "app.api_key")).isEqualTo(MASK);
        assertThat(value(masked, "db.statement")).isEqualTo("update users set password='" + MASK + "' where id=?");
        assertThat(value(masked, "http.route")).isEqualTo("/api/sample/{id}");
        assertThat(value(masked, "gen_ai.usage.input_tokens")).isEqualTo(42);
        assertThat(value(masked, "bootui.exception.type")).isEqualTo("java.lang.IllegalStateException");
        assertThat(value(masked, "error")).isEqualTo(true);

        List<SpanAttributeDto> metadata = METADATA_ONLY.attributes(attributes);
        assertThat(value(metadata, "app.api_key")).isEqualTo(MASK);
        assertThat(value(metadata, "db.statement")).isEqualTo("update users set password='" + MASK + "' where id=?");
        assertThat(value(metadata, "http.route")).isEqualTo("/api/sample/{id}");

        assertThat(FULL.attributes(attributes))
                .extracting(SpanAttributeDto::value)
                .doesNotContain(MASK);
        assertThat(UNMASKED.attributes(attributes))
                .extracting(SpanAttributeDto::value)
                .doesNotContain(MASK);
    }

    @Test
    void nestedListsAndKeyValueMapsAreMaskedLeafByLeaf() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("api_key", "k-7");
        request.put("note", "secret=s-7");
        request.put("retries", 3);
        Map<String, AttributeValue> attributes = ordered(
                "app.request", new AttributeValue("map", request),
                "app.notes", AttributeValue.ofList(List.of(List.of("password=p-7"), 1)),
                "exception", new AttributeValue("map", Map.of("message", "token=t-7")));

        List<SpanAttributeDto> masked = MASKED.attributes(attributes);
        assertThat(masked.toString()).doesNotContain("k-7", "s-7", "p-7", "t-7");
        assertThat(value(masked, "app.request"))
                .isEqualTo(Map.of("api_key", MASK, "note", "secret=" + MASK, "retries", 3));
        assertThat(value(masked, "app.notes")).isEqualTo(List.of(List.of("password=" + MASK), 1));
        assertThat(value(masked, "exception")).isEqualTo(Map.of("message", "token=" + MASK));

        List<SpanAttributeDto> metadata = METADATA_ONLY.attributes(attributes);
        assertThat(metadata.toString()).doesNotContain("k-7", "s-7", "p-7", "t-7");
        assertThat(value(metadata, "exception")).isEqualTo(java.util.Collections.singletonMap("message", null));

        assertThat(value(FULL.attributes(attributes), "app.request")).isEqualTo(request);
    }

    @Test
    void omittedTextIsOmittedWhateverItsRuntimeType() {
        Map<String, AttributeValue> attributes = ordered(
                "exception.message", AttributeValue.ofList(List.of("password=p-8", 8)),
                "url.query", AttributeValue.ofNumber(8));

        assertThat(METADATA_ONLY.attributes(attributes))
                .extracting(SpanAttributeDto::value)
                .containsOnlyNulls();
        assertThat(value(MASKED.attributes(attributes), "exception.message")).isEqualTo(List.of("password=" + MASK, 8));
    }

    @Test
    void bootUiPrefixedAttributesAreNotExempt() {
        Map<String, AttributeValue> attributes = ordered(
                "bootui.api_key", AttributeValue.ofString("k-9"),
                "bootui.service", AttributeValue.ofString("orders secret=s-9"),
                "bootui.sql.queries", AttributeValue.ofNumber(4));

        List<SpanAttributeDto> masked = MASKED.attributes(attributes);
        assertThat(value(masked, "bootui.api_key")).isEqualTo(MASK);
        assertThat(value(masked, "bootui.service")).isEqualTo("orders secret=" + MASK);
        assertThat(value(masked, "bootui.sql.queries")).isEqualTo(4);
    }

    @Test
    void generativeAiContentFollowsTheMessageRule() {
        Map<String, AttributeValue> attributes =
                ordered("gen_ai.prompt", AttributeValue.ofString("my token: t-5, summarize this"));

        assertThat(value(MASKED.attributes(attributes), "gen_ai.prompt"))
                .isEqualTo("my token: " + MASK + ", summarize this");
        assertThat(value(METADATA_ONLY.attributes(attributes), "gen_ai.prompt")).isNull();
    }

    @Test
    void toolCallArgumentsResultsAndVectorQueryContentFollowTheMessageRule() {
        List<String> keys = List.of(
                "gen_ai.tool.call.arguments",
                "gen_ai.tool.call.result",
                "spring.ai.tool.call.arguments",
                "spring.ai.tool.call.result",
                "db.vector.query.content",
                "db.vector.query.response.documents",
                "gen_ai.prompt.0.content",
                "gen_ai.completion.0.content");
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        for (String key : keys) {
            attributes.put(key, AttributeValue.ofString("{\"city\":\"Paris\",\"password\":\"pw-7\"}"));
        }

        List<SpanAttributeDto> masked = MASKED.attributes(attributes);
        List<SpanAttributeDto> omitted = METADATA_ONLY.attributes(attributes);
        List<SpanAttributeDto> full = FULL.attributes(attributes);
        for (String key : keys) {
            assertThat((String) value(masked, key)).as(key).contains("Paris").doesNotContain("pw-7");
            assertThat(value(omitted, key)).as(key).isNull();
            assertThat((String) value(full, key)).as(key).contains("pw-7");
        }
        assertThat(omitted).extracting(SpanAttributeDto::key).containsExactlyElementsOf(keys);
    }

    @Test
    void anUnresolvedExposureModeFailsClosedToMasked() {
        SpanValueExposure rule = rule(null, true);

        assertThat(rule.statusMessage("secret=s-6")).isEqualTo("secret=" + MASK);
        assertThat(rule.attributes(null)).isEmpty();
        assertThat(rule.events(null)).isEmpty();
    }

    private static Object value(List<SpanAttributeDto> attributes, String key) {
        return attributes.stream()
                .filter(attribute -> attribute.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing attribute " + key))
                .value();
    }

    private static Map<String, AttributeValue> ordered(Object... keysAndValues) {
        Map<String, AttributeValue> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], (AttributeValue) keysAndValues[i + 1]);
        }
        return map;
    }

    private static SpanValueExposure rule(ValueExposure exposure, boolean maskSecrets) {
        return SpanValueExposure.current(TracesServiceTests.policy(exposure, maskSecrets));
    }
}
