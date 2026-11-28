package io.github.jdubois.bootui.quarkus4.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.websockets.SampleEchoWebSocket;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus WebSockets panel light-up end to end on an app that <strong>does</strong> have the
 * {@code quarkus-websockets-next} extension on its classpath: the {@code @WebSocket} endpoint declared by
 * {@link SampleEchoWebSocket} is discovered at build time by the deployment processor's Jandex scan, recorded
 * into the synthetic {@code QuarkusWebSockets} bean, mapped by {@code QuarkusWebSocketMetadataProvider} onto
 * the neutral {@code WebSocketReport} contract, and surfaced on {@code GET /bootui/api/websockets} — with the
 * panel reported available in the manifest.
 *
 * <p>This is the websockets-<em>present</em> half of the coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the websockets-<em>absent</em> path (the panel is
 * reported unavailable and the report answers {@code available=false}).</p>
 *
 * <p>It also pins the honest capability statement: WebSockets Next exposes no message-interception SPI, so the
 * report answers {@code frameCaptureSupported=false} with a reason. No assertion here — and no code path —
 * ever reads a message payload.</p>
 */
@QuarkusTest
class BootUiQuarkusWebSocketsResourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void webSocketsPanelListsTheAnnotatedEndpoint() {
        Response response = probe().get("/bootui/api/websockets");
        assertThat(response.status()).as("GET /bootui/api/websockets status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/websockets content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();
        assertThat(root.path("available").asBoolean(false))
                .as("with quarkus-websockets-next present the report is available")
                .isTrue();
        assertThat(root.path("framework").asText()).as("framework label").isEqualTo("quarkus-websockets-next");

        JsonNode endpoint = endpointByPath(root, "/it/echo");
        assertThat(endpoint.path("handlerClass").asText())
                .as("handler class captured from the Jandex index")
                .isEqualTo(SampleEchoWebSocket.class.getName());
        assertThat(endpoint.path("kind").asText()).as("endpoint kind").isEqualTo("ENDPOINT");
        assertThat(endpoint.path("captureInstalled").asBoolean(true))
                .as("WebSockets Next has no message-interception SPI, so no frame capture is installed")
                .isFalse();

        assertThat(callbackTypes(endpoint))
                .as("the declared callbacks are captured by annotation name")
                .contains("ON_OPEN", "ON_TEXT_MESSAGE", "ON_CLOSE");
    }

    @Test
    void webSocketsReportStatesFrameCaptureIsUnsupportedWithAReason() {
        JsonNode root = probe().get("/bootui/api/websockets").json();
        assertThat(root.path("frameCaptureSupported").asBoolean(true))
                .as("WebSockets Next exposes no message-interception SPI")
                .isFalse();
        assertThat(root.path("frameCaptureUnavailableReason").asText(null))
                .as("the unsupported state is explained rather than silently empty")
                .isNotBlank();
        assertThat(root.path("capturing").asBoolean(true))
                .as("frame capture is never reported as active on a stack that cannot capture frames")
                .isFalse();
    }

    @Test
    void webSocketsPanelIsReportedAvailable() {
        Response response = probe().get("/bootui/api/panels");
        assertThat(response.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode panel = panelById(response.json(), "websockets");
        assertThat(panel.path("available").asBoolean(false))
                .as("the WebSockets panel is available when quarkus-websockets-next declares an endpoint")
                .isTrue();
    }

    @Test
    void clearAndCaptureActionsAnswerTheSameReportShape() {
        Response cleared = probe().request("DELETE", "/bootui/api/websockets", JSON_HEADERS, null);
        assertThat(cleared.status()).as("DELETE /bootui/api/websockets status").isEqualTo(200);
        assertThat(cleared.json().path("available").asBoolean(false))
                .as("clearing BootUI's own buffers leaves the panel available")
                .isTrue();
        assertThat(cleared.json().path("stats").path("capturedActivity").asInt(-1))
                .as("the activity buffer is empty after a clear")
                .isEqualTo(0);

        Response captured =
                probe().request("POST", "/bootui/api/websockets/capture", JSON_HEADERS, "{\"enabled\":true}");
        assertThat(captured.status())
                .as("POST /bootui/api/websockets/capture status")
                .isEqualTo(200);
        assertThat(captured.json().path("frameCaptureSupported").asBoolean(true))
                .as("asking to capture cannot make an unsupported capability supported")
                .isFalse();
    }

    private static JsonNode endpointByPath(JsonNode report, String path) {
        for (JsonNode endpoint : report.path("endpoints")) {
            if (path.equals(endpoint.path("path").asText(null))) {
                return endpoint;
            }
        }
        throw new AssertionError("No WebSocket endpoint with path " + path + " in " + report);
    }

    private static List<String> callbackTypes(JsonNode endpoint) {
        List<String> types = new ArrayList<>();
        for (JsonNode callback : endpoint.path("callbacks")) {
            types.add(callback.path("type").asText(null));
        }
        return types;
    }

    private static JsonNode panelById(JsonNode manifest, String id) {
        for (JsonNode panel : manifest.path("panels")) {
            if (id.equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        throw new AssertionError("No panel with id " + id + " in manifest " + manifest);
    }
}
