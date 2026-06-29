package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Log Tail panel ({@code LogTailResource} over the engine
 * {@code LogTailBuffer}, fed by {@code QuarkusLogTailHandler} on the JBoss LogManager). Verifies the
 * capture handler is installed against the live backend: an application log line is captured and surfaced
 * by {@code GET /bootui/api/log-tail/recent} with the byte-identical {@code LogLineDto} shape, and BootUI's
 * own loggers are excluded so the panel never tails its internals.
 */
@QuarkusTest
class BootUiQuarkusLogTailResourceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void capturesApplicationLogLinesWithTheLogLineDtoShape() {
        String marker = "log-tail-probe-" + System.nanoTime();
        Logger.getLogger("com.example.quarkus.LogTailProbe").warning(marker);

        Response response = probe().get("/bootui/api/log-tail/recent");
        assertThat(response.status())
                .as("GET /bootui/api/log-tail/recent status")
                .isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/log-tail/recent content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.isArray()).as("$ must be an array of log lines").isTrue();

        boolean found = false;
        boolean leaksBootUiLogger = false;
        for (JsonNode line : body) {
            assertThat(line.has("timestamp") && line.has("level") && line.has("logger") && line.has("message"))
                    .as("each line must carry the LogLineDto fields")
                    .isTrue();
            if (marker.equals(line.path("message").asText())) {
                found = true;
                assertThat(line.path("level").asText()).isEqualTo("WARN");
                assertThat(line.path("logger").asText()).isEqualTo("com.example.quarkus.LogTailProbe");
            }
            if (line.path("logger").asText().startsWith("io.github.jdubois.bootui.quarkus")) {
                leaksBootUiLogger = true;
            }
        }
        assertThat(found)
                .as("the just-emitted application log line must be captured")
                .isTrue();
        assertThat(leaksBootUiLogger)
                .as("BootUI's own loggers must be excluded from the tail")
                .isFalse();
    }
}
