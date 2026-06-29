package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Loggers panel ({@code LoggersResource} over the engine
 * {@code LoggersService} backed by {@code QuarkusLoggerProvider} / the JBoss LogManager). The level
 * mapping is unit-tested in {@code QuarkusLoggerProviderTest}; the happy-path write round-trip is also in
 * the shared conformance suite. This test pins the Quarkus-specific behavior: the live backend is detected
 * and enumerated with the canonical level vocabulary, and the engine's fail-closed write guard rejects a
 * change to one of BootUI's own loggers with a 400 (so a Quarkus mutation endpoint cannot be turned
 * against BootUI's internals).
 */
@QuarkusTest
class BootUiQuarkusLoggersResourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void listsLiveLoggersWithTheCanonicalLevelVocabulary() {
        Response response = probe().get("/bootui/api/loggers");
        assertThat(response.status()).as("GET /bootui/api/loggers status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/loggers content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.path("availableLevels").isArray())
                .as("$.availableLevels must be an array")
                .isTrue();
        java.util.List<String> levels = new java.util.ArrayList<>();
        body.path("availableLevels").forEach(level -> levels.add(level.asText()));
        assertThat(levels)
                .as("the JBoss LogManager backend must surface BootUI's canonical level vocabulary")
                .containsExactly("OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");
        assertThat(body.path("loggers").isArray() && body.path("loggers").size() > 0)
                .as("a booted Quarkus app exposes at least one logger")
                .isTrue();
    }

    @Test
    void setsAndReadsBackAnApplicationLoggerLevel() {
        String logger = "com.example.quarkus.LoggersProbe";

        Response set = probe().request("POST", "/bootui/api/loggers/" + logger, JSON_HEADERS, "{\"level\":\"DEBUG\"}");
        assertThat(set.status()).as("POST set-level status").isEqualTo(200);
        JsonNode updated = set.json();
        assertThat(updated.path("name").asText()).isEqualTo(logger);
        assertThat(updated.path("configuredLevel").asText()).isEqualTo("DEBUG");
        assertThat(updated.path("effectiveLevel").asText()).isEqualTo("DEBUG");
    }

    @Test
    void refusesToMutateOneOfBootUisOwnLoggers() {
        String ownLogger = "io.github.jdubois.bootui.quarkus.Probe";

        Response response =
                probe().request("POST", "/bootui/api/loggers/" + ownLogger, JSON_HEADERS, "{\"level\":\"DEBUG\"}");

        assertThat(response.status())
                .as("changing a BootUI-owned logger must be rejected with 400")
                .isEqualTo(400);
        assertThat(response.isJson())
                .as("the 400 body must be JSON (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("error").asText())
                .as("the 400 body must name the rejected logger")
                .contains(ownLogger);
    }
}
