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
 * Proves the Quarkus capture layer: a request through the host app's own loopback port is sampled into
 * the shared engine ring buffer by the Vert.x capture filter and surfaced by both
 * {@code GET /bootui/api/http-exchanges} and {@code GET /bootui/api/activity}. Also pins that
 * sensitive headers are masked, BootUI's own traffic is self-excluded, and Live Activity honestly
 * declares SQL/exceptions unavailable.
 */
@QuarkusTest
class BootUiQuarkusHttpExchangesCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void httpExchangesCaptureHostRequestAndMaskAuthorization() {
        BootUiHttpProbe probe = probe();
        probe.get("/api/hello", Map.of("Authorization", "Bearer supersecret"));

        Response response = probe.get("/bootui/api/http-exchanges");
        assertThat(response.status()).as("http-exchanges status").isEqualTo(200);
        assertThat(response.isJson()).isTrue();

        JsonNode report = response.json();
        assertThat(report.path("recorded").asInt())
                .as("at least one exchange recorded")
                .isGreaterThan(0);

        boolean foundHello = false;
        boolean foundSelf = false;
        for (JsonNode ex : report.path("exchanges")) {
            String path = ex.path("path").asText("");
            if (path.equals("/api/hello")) {
                foundHello = true;
                for (JsonNode header : ex.path("requestHeaders")) {
                    if (header.path("name").asText().equalsIgnoreCase("authorization")) {
                        assertThat(header.path("masked").asBoolean())
                                .as("authorization masked")
                                .isTrue();
                        assertThat(header.path("values").path(0).asText()).isEqualTo("******");
                    }
                }
            }
            if (path.startsWith("/bootui")) {
                foundSelf = true;
            }
        }
        assertThat(foundHello).as("captured host request to /api/hello").isTrue();
        assertThat(foundSelf).as("BootUI's own traffic is self-excluded").isFalse();
    }

    @Test
    void liveActivityMergesRequestsAndDegradesSqlCleanly() {
        BootUiHttpProbe probe = probe();
        probe.get("/api/hello");

        Response response = probe.get("/bootui/api/activity");
        assertThat(response.status()).as("activity status").isEqualTo(200);
        JsonNode report = response.json();
        assertThat(report.path("available").asBoolean()).isTrue();
        assertThat(report.path("typeCounts").path("REQUEST").asInt()).isGreaterThan(0);
        assertThat(report.path("warnings").isArray()).isTrue();
        assertThat(report.path("warnings").toString()).contains("Quarkus");
    }
}
