package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Writes one secret-bearing application log line, holding secret assignments and a credential after an authorization
 * scheme, and checks how a Log Tail surface returns it: the recent snapshot,
 * the SSE stream, {@code get_log_tail} over MCP, and the same tool over the CLI endpoint. Every conformance runner
 * boots the application in the test JVM, and both Spring (through its {@code java.util.logging} bridge) and Quarkus
 * (through the JBoss LogManager) route a {@code java.util.logging} record into Log Tail capture.
 *
 * <p>Each contract logs under its own logger name, so its line can be found even when the exposure policy omits the
 * message. {@link #withExposure} changes the policy through JVM system properties, which both Spring's live
 * {@code Environment} and Quarkus' MicroProfile Config read on every call, to prove a runtime change applies without a
 * restart.</p>
 */
final class LogTailExposureContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    final String logger = "com.example.conformance.LogTailExposure"
            + UUID.randomUUID().toString().replace("-", "");

    private final String marker = "log-tail-exposure-" + UUID.randomUUID();

    private final String password = "pw-" + UUID.randomUUID();

    private final String apiKey = "ak-" + UUID.randomUUID();

    private final String bearerToken = "tok-" + UUID.randomUUID();

    /** Logs the line at {@code WARN}, so every adapter's default threshold keeps it. */
    LogTailExposureContract log() {
        Logger.getLogger(logger).warning(verbatim());
        return this;
    }

    /** Asserts that {@code lines}, a {@code LogLineDto} array, holds the line with every secret masked. */
    void assertMaskedIn(JsonNode lines, String surface) {
        assertMasked(find(lines, surface), surface);
    }

    /** Asserts that {@code lines} holds the line with its message omitted and its metadata kept. */
    void assertOmittedIn(JsonNode lines, String surface) {
        assertOmitted(find(lines, surface), surface);
    }

    /** Asserts that {@code lines} holds the line with its message verbatim. */
    void assertVerbatimIn(JsonNode lines, String surface) {
        assertVerbatim(find(lines, surface), surface);
    }

    void assertMaskedInStream(String body, String surface) {
        assertMasked(findInStream(body, surface), surface);
    }

    void assertOmittedInStream(String body, String surface) {
        assertOmitted(findInStream(body, surface), surface);
    }

    private JsonNode find(JsonNode lines, String surface) {
        JsonNode line = null;
        for (JsonNode candidate : lines) {
            if (logger.equals(candidate.path("logger").asText())) {
                line = candidate;
            }
        }
        assertThat(line).as("%s must return the logged line", surface).isNotNull();
        return line;
    }

    private JsonNode findInStream(String body, String surface) {
        JsonNode line = null;
        for (String row : body.split("\\R")) {
            if (row.startsWith("data:") && row.contains(logger)) {
                line = json(row.substring("data:".length()).trim());
            }
        }
        assertThat(line)
                .as("%s must stream the logged line; received %s", surface, body)
                .isNotNull();
        return line;
    }

    private void assertMasked(JsonNode line, String surface) {
        assertThat(line.toString())
                .as("%s must never carry a secret value", surface)
                .doesNotContain(password, apiKey, bearerToken);
        assertThat(line.path("message").asText())
                .as("%s message", surface)
                .isEqualTo(marker + " login password=******\nretrying with api_key: ******"
                        + "\ncalling api with Authorization: Bearer ******");
        assertMessageOmitted(line, false, surface);
        assertMetadata(line, surface);
    }

    private void assertOmitted(JsonNode line, String surface) {
        assertThat(line.toString())
                .as("%s must carry no message text", surface)
                .doesNotContain(marker, password, bearerToken);
        assertThat(line.has("message"))
                .as("%s keeps the message field", surface)
                .isTrue();
        assertThat(line.path("message").isNull()).as("%s message", surface).isTrue();
        assertMessageOmitted(line, true, surface);
        assertMetadata(line, surface);
    }

    private void assertVerbatim(JsonNode line, String surface) {
        assertThat(line.path("message").asText()).as("%s message", surface).isEqualTo(verbatim());
        assertMessageOmitted(line, false, surface);
        assertMetadata(line, surface);
    }

    private static void assertMessageOmitted(JsonNode line, boolean expected, String surface) {
        assertThat(line.path("messageOmitted").isBoolean())
                .as("%s messageOmitted", surface)
                .isTrue();
        assertThat(line.path("messageOmitted").booleanValue())
                .as("%s messageOmitted", surface)
                .isEqualTo(expected);
    }

    private void assertMetadata(JsonNode line, String surface) {
        assertThat(line.path("timestamp").isNumber())
                .as("%s timestamp", surface)
                .isTrue();
        assertThat(line.path("level").asText()).as("%s level", surface).isEqualTo("WARN");
        assertThat(line.path("logger").asText()).as("%s logger", surface).isEqualTo(logger);
        assertThat(line.path("thread").isTextual()).as("%s thread", surface).isTrue();
    }

    private String verbatim() {
        return marker + " login password=" + password + "\nretrying with api_key: " + apiKey
                + "\ncalling api with Authorization: Bearer " + bearerToken;
    }

    /**
     * Runs {@code body} with {@code bootui.expose-values} and {@code bootui.mask-secrets} set as JVM system
     * properties, restoring their previous values afterwards. A {@code null} value clears the property.
     */
    static void withExposure(String exposeValues, String maskSecrets, Runnable body) {
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("bootui.expose-values", System.getProperty("bootui.expose-values"));
        previous.put("bootui.mask-secrets", System.getProperty("bootui.mask-secrets"));
        try {
            set("bootui.expose-values", exposeValues);
            set("bootui.mask-secrets", maskSecrets);
            body.run();
        } finally {
            previous.forEach(LogTailExposureContract::set);
        }
    }

    static void setExposure(String exposeValues) {
        set("bootui.expose-values", exposeValues);
    }

    private static void set(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException ex) {
            throw new AssertionError("Expected JSON but got: " + text, ex);
        }
    }
}
