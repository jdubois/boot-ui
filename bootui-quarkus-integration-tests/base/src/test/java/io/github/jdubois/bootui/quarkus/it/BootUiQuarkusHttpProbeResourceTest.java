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
 * Real-boot checks for the Quarkus HTTP Probe panel ({@code HttpProbeResource} over the engine
 * {@code HttpProbeService} backed by {@code QuarkusServerPortSupplier}).
 *
 * <p>This is also the ground truth for the launch-mode port resolution: under {@code @QuarkusTest} the
 * app binds to the <em>test</em> port, so a successful self-probe of {@code /bootui/api/overview}
 * (returning {@code 200}) only happens if {@code QuarkusServerPortSupplier} resolved
 * {@code quarkus.http.test-port} rather than {@code quarkus.http.port}. The probe always targets the
 * application's own loopback, so it also exercises that an unknown path comes back {@code 404} — proving
 * the engine reached a live server, not that it failed to connect.</p>
 */
@QuarkusTest
class BootUiQuarkusHttpProbeResourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void probesTheApplicationsOwnLoopbackPortAndReachesAServedEndpoint() {
        Response response = probe().request(
                        "POST",
                        "/bootui/api/http-probe",
                        JSON_HEADERS,
                        "{\"method\":\"GET\",\"path\":\"/bootui/api/overview\"}");

        assertThat(response.status()).as("POST /bootui/api/http-probe status").isEqualTo(200);
        assertThat(response.isJson())
                .as("the probe response must be JSON (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.path("status").asInt())
                .as("the probe must reach the live (test-port) server's /bootui/api/overview, returning 200")
                .isEqualTo(200);
        assertThat(body.path("statusText").asText()).isEqualTo("OK");
        assertThat(body.path("error").isNull() || body.path("error").isMissingNode())
                .as("a successful probe carries no error")
                .isTrue();
        assertThat(body.path("headers").path("content-type").asText())
                .as("the surfaced response headers include the probed endpoint's content-type")
                .contains("application/json");
        assertThat(body.path("body").asText())
                .as("the probed /bootui/api/overview body is returned verbatim")
                .contains("frameworkName");
    }

    @Test
    void reportsTheStatusOfAnUnknownLoopbackPath() {
        Response response = probe().request(
                        "POST",
                        "/bootui/api/http-probe",
                        JSON_HEADERS,
                        "{\"method\":\"GET\",\"path\":\"/bootui/api/__no_such_endpoint__\"}");

        assertThat(response.status()).as("POST /bootui/api/http-probe status").isEqualTo(200);
        assertThat(response.json().path("status").asInt())
                .as("reaching a live server with an unknown path yields 404 (not a connection error)")
                .isEqualTo(404);
    }

    @Test
    void rejectsAnOversizedRequestBodyWithTheCanonicalBadRequestBody() {
        String oversized = "a".repeat(64 * 1024 + 1);
        Response response = probe().request(
                        "POST",
                        "/bootui/api/http-probe",
                        JSON_HEADERS,
                        "{\"method\":\"POST\",\"path\":\"/bootui/api/overview\",\"body\":\"" + oversized + "\"}");

        assertThat(response.status())
                .as("an over-limit probe body is invalid input, not a probe outcome")
                .isEqualTo(400);
        assertThat(response.isJson())
                .as("the rejection body must be JSON (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("error").asText())
                .isEqualTo("HTTP Probe request body exceeds the maximum of 65536 bytes");
    }

    @Test
    void rejectsTooManyRequestHeadersWithTheCanonicalBadRequestBody() {
        StringBuilder headers = new StringBuilder();
        for (int i = 0; i <= 50; i++) {
            if (i > 0) {
                headers.append(',');
            }
            headers.append("\"X-Probe-").append(i).append("\":\"v\"");
        }
        Response response = probe().request(
                        "POST",
                        "/bootui/api/http-probe",
                        JSON_HEADERS,
                        "{\"method\":\"GET\",\"path\":\"/bootui/api/overview\",\"headers\":{" + headers + "}}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText())
                .isEqualTo("HTTP Probe request exceeds the maximum of 50 request headers");
    }
}
