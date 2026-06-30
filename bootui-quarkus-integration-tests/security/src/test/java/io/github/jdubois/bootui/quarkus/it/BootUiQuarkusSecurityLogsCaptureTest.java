package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot check for the Quarkus Security Logs panel ({@code SecurityLogsResource} over the engine
 * {@code SecurityLogsService} backed by the {@code QuarkusSecurityEventCapture} CDI observer). With
 * quarkus-security present and {@code quarkus.security.events.enabled=true}, hitting a role-protected
 * endpoint fires authentication/authorization CDI events; this pins that at least one is captured, masked
 * and surfaced, and that the panel is reported available in the manifest.
 */
@QuarkusTest
class BootUiQuarkusSecurityLogsCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void capturesAuthenticationEvents() {
        String authorized =
                "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        probe().get("/secure", Map.of("Authorization", authorized));

        Response response = probe().get("/bootui/api/security-logs");
        assertThat(response.status()).as("GET /bootui/api/security-logs status").isEqualTo(200);
        assertThat(response.isJson())
                .as("content-type %s", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.path("auditEventsPresent").asBoolean())
                .as("$.auditEventsPresent")
                .isTrue();
        assertThat(body.path("events").isArray()).isTrue();
        assertThat(body.path("events").size())
                .as("at least one captured security event")
                .isGreaterThan(0);

        boolean hasAuthEvent = false;
        for (JsonNode event : body.path("events")) {
            if (event.path("type").asText().startsWith("Authentication")) {
                hasAuthEvent = true;
                break;
            }
        }
        assertThat(hasAuthEvent).as("an authentication event must be captured").isTrue();
    }

    @Test
    void panelReportedAvailable() {
        Response response = probe().get("/bootui/api/panels");
        assertThat(response.status()).isEqualTo(200);
        boolean available = false;
        for (JsonNode panel : response.json().path("panels")) {
            if ("security-logs".equals(panel.path("id").asText())) {
                available = panel.path("available").asBoolean();
            }
        }
        assertThat(available)
                .as("Security Logs panel must be available when events are enabled")
                .isTrue();
    }

    @Test
    void malformedAfterTimestampReturns400() {
        // With events enabled the `after` param is parsed; a malformed value must be a 400 (client error),
        // not a 500, matching the Spring controller's @ExceptionHandler contract: {"error": <message>}.
        Response response = probe().get("/bootui/api/security-logs?after=not-a-timestamp");
        assertThat(response.status())
                .as("a malformed `after` query param must be a 400, not a 500")
                .isEqualTo(400);
        assertThat(response.isJson())
                .as("content-type %s", response.contentType())
                .isTrue();
        assertThat(response.json().path("error").asText()).as("$.error message").isNotBlank();
    }
}
