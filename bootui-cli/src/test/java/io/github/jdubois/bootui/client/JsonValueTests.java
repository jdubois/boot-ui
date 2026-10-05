package io.github.jdubois.bootui.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonValueTests {

    @Test
    void parsesTheScalarTypes() {
        JsonValue json = JsonValue.parse("{\"s\":\"text\",\"n\":42,\"d\":1.5,\"b\":true,\"z\":null}");

        assertThat(json.isObject()).isTrue();
        assertThat(json.get("s").asString("")).isEqualTo("text");
        assertThat(json.get("n").asInt(0)).isEqualTo(42);
        assertThat(json.get("d").asDouble(0)).isEqualTo(1.5);
        assertThat(json.get("b").asBoolean(false)).isTrue();
        assertThat(json.get("z").isNull()).isTrue();
    }

    @Test
    void aNumberKeepsItsOriginalTextSoDisplayDoesNotInventPrecision() {
        JsonValue json = JsonValue.parse("{\"a\":1.10,\"b\":1e3,\"c\":-0.5}");

        assertThat(json.get("a").asDisplayText()).isEqualTo("1.10");
        assertThat(json.get("b").asDisplayText()).isEqualTo("1e3");
        assertThat(json.get("b").asDouble(0)).isEqualTo(1000d);
        assertThat(json.get("c").asDouble(0)).isEqualTo(-0.5);
    }

    @Test
    void aNumberTooLargeForALongStillReadsAsADouble() {
        JsonValue json = JsonValue.parse("{\"big\":123456789012345678901234567890}");

        assertThat(json.get("big").asDouble(0)).isGreaterThan(1e29);
        assertThat(json.get("big").asDisplayText()).isEqualTo("123456789012345678901234567890");
    }

    @Test
    void preservesMemberOrderSoRenderedOutputMatchesTheServer() {
        JsonValue json = JsonValue.parse("{\"z\":1,\"a\":2,\"m\":3}");

        assertThat(json.names()).containsExactly("z", "a", "m");
    }

    @Test
    void readsNestedArraysAndObjects() {
        JsonValue json = JsonValue.parse("{\"items\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");

        assertThat(json.get("items").size()).isEqualTo(2);
        assertThat(json.get("items").get(1).get("id").asString("")).isEqualTo("b");
        assertThat(json.get("items").get(9).isMissing()).isTrue();
    }

    @Test
    void decodesEveryEscapeSequence() {
        JsonValue json = JsonValue.parse("\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\\u00e9\"");

        assertThat(json.asString("")).isEqualTo("a\"b\\c/d\be\ff\ng\rh\ti\u00e9");
    }

    @Test
    void aLookupThatDoesNotResolveIsMissingRatherThanNull() {
        JsonValue json = JsonValue.parse("{\"a\":1}");

        assertThat(json.get("nope").isMissing()).isTrue();
        assertThat(json.get("nope").get("deeper").isMissing()).isTrue();
        assertThat(json.get("nope").asString("fallback")).isEqualTo("fallback");
    }

    @Test
    void accessorsReturnTheFallbackOnATypeMismatchRatherThanThrowing() {
        JsonValue json = JsonValue.parse("{\"a\":\"text\"}");

        assertThat(json.get("a").asInt(7)).isEqualTo(7);
        assertThat(json.get("a").asBoolean(true)).isTrue();
        assertThat(json.get("a").get(0).isMissing()).isTrue();
    }

    @Test
    void reEmitsAsCompactJson() {
        String source = "{\"a\":[1,2,{\"b\":\"c\"}],\"d\":null,\"e\":true}";

        assertThat(JsonValue.parse(source).toJson()).isEqualTo(source);
    }

    @Test
    void reEmittedStringsAreEscapedSoTheOutputParsesBack() {
        JsonValue json = JsonValue.of("line\nbreak \"quoted\" \u0001control");

        assertThat(JsonValue.parse(json.toJson()).asString("")).isEqualTo("line\nbreak \"quoted\" \u0001control");
    }

    @Test
    void buildsValuesForRequestBodies() {
        String body = JsonWriter.object(Map.of("query", JsonValue.of("spring")));

        assertThat(body).isEqualTo("{\"query\":\"spring\"}");
        assertThat(JsonValue.array(List.of(JsonValue.of(1), JsonValue.of(true))).toJson())
                .isEqualTo("[1,true]");
    }

    @Test
    void prettyPrintsForReading() {
        String pretty = JsonWriter.pretty(JsonValue.parse("{\"a\":[1,2],\"b\":{},\"c\":[]}"));

        assertThat(pretty).isEqualTo("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {},\n  \"c\": []\n}");
    }

    @Test
    void refusesContentThatIsNotJsonInsteadOfGuessing() {
        assertThatThrownBy(() -> JsonValue.parse("<html>Gateway Timeout</html>"))
                .isInstanceOf(JsonParseException.class);
        assertThatThrownBy(() -> JsonValue.parse("{\"a\":1")).isInstanceOf(JsonParseException.class);
        assertThatThrownBy(() -> JsonValue.parse("{\"a\":1}trailing")).isInstanceOf(JsonParseException.class);
        assertThatThrownBy(() -> JsonValue.parse("{a:1}")).isInstanceOf(JsonParseException.class);
        assertThatThrownBy(() -> JsonValue.parse("[1,]")).isInstanceOf(JsonParseException.class);
        assertThatThrownBy(() -> JsonValue.parse("")).isInstanceOf(JsonParseException.class);
        assertThatThrownBy(() -> JsonValue.parse(null)).isInstanceOf(JsonParseException.class);
    }

    @Test
    void refusesDocumentsNestedDeeplyEnoughToExhaustTheStack() {
        String deep = "[".repeat(5000) + "]".repeat(5000);

        assertThatThrownBy(() -> JsonValue.parse(deep))
                .isInstanceOf(JsonParseException.class)
                .hasMessageContaining("too deep");
    }

    @Test
    void acceptsWhitespaceAnywhereItIsLegal() {
        JsonValue json = JsonValue.parse("  {\n  \"a\" :\t[ 1 , 2 ]\r\n}  ");

        assertThat(json.get("a").size()).isEqualTo(2);
    }

    @Test
    void leadingZerosAreRejectedBecauseTheReaderPromisesRfc8259AndNothingElse() {
        assertThatThrownBy(() -> JsonValue.parse("{\"a\":01}")).isInstanceOf(JsonParseException.class);
        assertThatThrownBy(() -> JsonValue.parse("007")).isInstanceOf(JsonParseException.class);
        assertThatThrownBy(() -> JsonValue.parse("-01")).isInstanceOf(JsonParseException.class);

        // The forms RFC 8259 does allow must keep parsing.
        assertThat(JsonValue.parse("0").toJson()).isEqualTo("0");
        assertThat(JsonValue.parse("-0").toJson()).isEqualTo("-0");
        assertThat(JsonValue.parse("0.5").toJson()).isEqualTo("0.5");
        assertThat(JsonValue.parse("-0.5e-3").toJson()).isEqualTo("-0.5e-3");
        assertThat(JsonValue.parse("10").toJson()).isEqualTo("10");
    }
}
