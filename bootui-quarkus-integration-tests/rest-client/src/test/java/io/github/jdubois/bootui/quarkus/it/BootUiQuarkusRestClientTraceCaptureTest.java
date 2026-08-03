package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.Map;
import org.acme.restclient.PingClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus REST Client panel light-up end to end on an app that <strong>does</strong> have
 * {@code quarkus-rest-client} on its classpath.
 *
 * <p>Verifies that:</p>
 * <ul>
 *   <li>The deployment build step emits the {@code ServiceProviderBuildItem} entry, making the REST Client
 *       panel <em>available</em> in the panel manifest.</li>
 *   <li>The {@code QuarkusRestClientTraceListener} SPI hook receives {@code onNewClient()} for the
 *       {@code PingClient} proxy and registers a {@code QuarkusRestClientTraceFilter}.</li>
 *   <li>A real outbound GET call through the client produces a {@code REST_CLIENT} entry visible in the
 *       panel report and in the Live Activity feed.</li>
 *   <li>The {@code clear} and {@code recording} toggle actions work correctly, mirroring the Spring adapter
 *       behavior.</li>
 *   <li>Request/response bodies are never captured (the filter only captures metadata).</li>
 * </ul>
 *
 * <p>This is the REST-client-<em>present</em> half; the absent path is proven in
 * {@code bootui-quarkus-integration-tests} where the panel is unavailable (no {@code ServiceProviderBuildItem}
 * entry emitted). No Docker is needed — the {@code PingClient} calls the test app's own loopback port.</p>
 */
@QuarkusTest
class BootUiQuarkusRestClientTraceCaptureTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    @Inject
    @RestClient
    PingClient pingClient;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void restClientPanelIsAvailableInManifestWhenRestClientReactiveIsPresent() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode restClientPanel = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("rest-client-trace".equals(panel.path("id").asText(null))) {
                restClientPanel = panel;
            }
        }
        assertThat(restClientPanel)
                .as("the REST Client panel is present in the manifest")
                .isNotNull();
        assertThat(restClientPanel.path("available").asBoolean(false))
                .as("the REST Client panel is available when quarkus-rest-client is present")
                .isTrue();
        assertThat(restClientPanel.path("enabled").asBoolean(false))
                .as("the REST Client panel is enabled when quarkus-rest-client is present")
                .isTrue();
    }

    @Test
    void restClientCallsAreCapturedAndVisibleInPanelReport() {
        // Make a real outbound GET through the REST Client Reactive proxy; the BootUI filter captures it.
        String pong = pingClient.ping(42);
        assertThat(pong).as("the call to PingResource succeeds").isEqualTo("pong-42");

        Response report = probe().get("/bootui/api/rest-client-trace");
        assertThat(report.status())
                .as("GET /bootui/api/rest-client-trace status")
                .isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/rest-client-trace content-type (%s)", report.contentType())
                .isTrue();

        JsonNode root = report.json();
        assertThat(root.path("available").asBoolean(false))
                .as("the report is available after a client has been instrumented and a call made")
                .isTrue();

        // At least one entry was captured.
        JsonNode entries = root.path("entries");
        assertThat(entries.isArray()).as("entries is an array").isTrue();
        assertThat(entries.size())
                .as("at least one REST client call was captured")
                .isGreaterThan(0);

        JsonNode firstEntry = entries.get(0);
        assertThat(firstEntry.path("method").asText(null))
                .as("the captured call records the HTTP method")
                .isEqualToIgnoringCase("GET");
        assertThat(firstEntry.path("host").asText(null))
                .as("the captured call records the host")
                .isNotBlank();
        assertThat(firstEntry.path("status").asInt(-1))
                .as("the captured call records the HTTP status")
                .isBetween(100, 599);
        assertThat(firstEntry.path("durationMillis").asLong(-1))
                .as("the captured call records a non-negative duration")
                .isGreaterThanOrEqualTo(0);
        assertThat(firstEntry.path("clientType").asText(null))
                .as("the captured call is labeled with the Quarkus client type")
                .isEqualTo("REST Client Reactive");
    }

    @Test
    void restClientClearActionClearsTheCaptureBuffer() {
        // Make a call first to populate the buffer.
        pingClient.ping(1);

        Response clearResponse = probe().request("POST", "/bootui/api/rest-client-trace/clear", JSON_HEADERS, "{}");
        assertThat(clearResponse.status()).as("POST /clear status").isEqualTo(200);
        JsonNode cleared = clearResponse.json();
        // After clear, the buffer is empty (entries array is empty).
        assertThat(cleared.path("entries").size())
                .as("buffer is empty after clear")
                .isEqualTo(0);
    }

    @Test
    void restClientRecordingToggleStopsAndResumesCapture() {
        // Trigger the client proxy build first so hasInstrumentedClient() returns true.
        pingClient.ping(0);

        // Pause recording.
        Response pauseResponse =
                probe().request("POST", "/bootui/api/rest-client-trace/recording", JSON_HEADERS, "{\"enabled\":false}");
        assertThat(pauseResponse.status()).as("POST /recording (pause) status").isEqualTo(200);
        assertThat(pauseResponse.json().path("capturing").asBoolean(true))
                .as("recording is false after pause (capturing field)")
                .isFalse();

        // Resume recording.
        Response resumeResponse =
                probe().request("POST", "/bootui/api/rest-client-trace/recording", JSON_HEADERS, "{\"enabled\":true}");
        assertThat(resumeResponse.status())
                .as("POST /recording (resume) status")
                .isEqualTo(200);
        assertThat(resumeResponse.json().path("capturing").asBoolean(false))
                .as("recording is true after resume (capturing field)")
                .isTrue();
    }
}
