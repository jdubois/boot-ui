package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Writes one secret-bearing application log line and checks how a Log Tail surface returns it under the default
 * {@code MASKED} exposure: the recent snapshot, the SSE stream, {@code get_log_tail} over MCP, and the same tool over
 * the CLI endpoint. Every conformance runner boots the application in the test JVM, and both Spring (through its
 * {@code java.util.logging} bridge) and Quarkus (through the JBoss LogManager) route a {@code java.util.logging}
 * record into Log Tail capture.
 */
final class LogTailExposureContract {

    static final String LOGGER = "com.example.conformance.LogTailExposure";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    final String marker = "log-tail-exposure-" + UUID.randomUUID();

    private final String password = "pw-" + UUID.randomUUID();

    private final String apiKey = "ak-" + UUID.randomUUID();

    /** Logs the line at {@code WARN}, so every adapter's default threshold keeps it. */
    LogTailExposureContract log() {
        Logger.getLogger(LOGGER).warning(marker + " login password=" + password + "\nretrying with api_key: " + apiKey);
        return this;
    }

    /** Asserts that {@code lines}, a {@code LogLineDto} array, holds the line with every secret masked. */
    void assertMaskedIn(JsonNode lines, String surface) {
        JsonNode line = null;
        for (JsonNode candidate : lines) {
            if (candidate.path("message").asText("").contains(marker)) {
                line = candidate;
            }
        }
        assertThat(line).as("%s must return the logged line", surface).isNotNull();
        assertMasked(line, surface);
    }

    /** Asserts that the SSE body carries the line with every secret masked. */
    void assertMaskedInStream(String body, String surface) {
        assertThat(body).as("%s must never carry a secret value", surface).doesNotContain(password, apiKey);
        JsonNode line = null;
        for (String row : body.split("\\R")) {
            if (row.startsWith("data:") && row.contains(marker)) {
                line = json(row.substring("data:".length()).trim());
            }
        }
        assertThat(line)
                .as("%s must stream the logged line; received %s", surface, body)
                .isNotNull();
        assertMasked(line, surface);
    }

    private void assertMasked(JsonNode line, String surface) {
        assertThat(line.path("message").asText())
                .as("%s message", surface)
                .isEqualTo(marker + " login password=******\nretrying with api_key: ******");
        assertThat(line.path("messageOmitted").isBoolean())
                .as("%s messageOmitted", surface)
                .isTrue();
        assertThat(line.path("messageOmitted").booleanValue())
                .as("%s messageOmitted", surface)
                .isFalse();
        assertThat(line.path("timestamp").isNumber())
                .as("%s timestamp", surface)
                .isTrue();
        assertThat(line.path("level").asText()).as("%s level", surface).isEqualTo("WARN");
        assertThat(line.path("logger").asText()).as("%s logger", surface).isEqualTo(LOGGER);
        assertThat(line.path("thread").isTextual()).as("%s thread", surface).isTrue();
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException ex) {
            throw new AssertionError("Expected JSON but got: " + text, ex);
        }
    }
}
