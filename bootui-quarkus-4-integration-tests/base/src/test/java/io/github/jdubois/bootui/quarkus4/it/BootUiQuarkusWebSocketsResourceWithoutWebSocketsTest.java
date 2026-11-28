package io.github.jdubois.bootui.quarkus4.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Pins the WebSockets panel's behavior on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-websockets-next} on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the websockets-<em>absent</em> half of the panel coverage (the present path lives in the
 * {@code bootui-quarkus-websockets-integration-tests} module). It proves the class-presence gate fails closed:
 * with {@code io.quarkus.websockets.next.WebSocketConnection} absent, the deployment processor registers no
 * connection-observing beans and produces no synthetic {@code QuarkusWebSockets} bean, so
 * {@code GET /bootui/api/websockets} answers with valid JSON reporting {@code available=false}, and the panel
 * is reported <em>unavailable</em> in the manifest with an honest extension hint (its
 * {@code bootui.internal.websockets-present} default stays {@code false}).</p>
 */
@QuarkusTest
class BootUiQuarkusWebSocketsResourceWithoutWebSocketsTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void webSocketsPanelIsUnavailableWithAnExtensionHintWithoutWebSocketsNext() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode websockets = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("websockets".equals(panel.path("id").asText(null))) {
                websockets = panel;
            }
        }
        assertThat(websockets)
                .as("the WebSockets panel is present in the manifest")
                .isNotNull();
        assertThat(websockets.path("available").asBoolean(true))
                .as("the WebSockets panel is unavailable when quarkus-websockets-next is absent")
                .isFalse();
        assertThat(websockets.path("unavailableReason").asText(null))
                .as("the unavailable reason names the extension to add, not the generic 'not yet' reason")
                .contains("quarkus-websockets-next");
    }

    @Test
    void webSocketsResourceRendersAnUnavailableReportWithoutWebSocketsNext() {
        Response response = probe().get("/bootui/api/websockets");
        assertThat(response.status()).as("GET /bootui/api/websockets status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/websockets content-type (%s)", response.contentType())
                .isTrue();
        JsonNode body = response.json();
        assertThat(body.path("available").asBoolean(true))
                .as("with no WebSockets extension the report is unavailable rather than empty-but-available")
                .isFalse();
        assertThat(body.path("unavailableReason").asText(null))
                .as("the unavailable report explains itself")
                .isNotBlank();
        assertThat(body.path("endpoints").size())
                .as("no endpoints without the extension")
                .isZero();
        assertThat(body.path("frameCaptureSupported").asBoolean(true))
                .as("no frame capture without the extension")
                .isFalse();
    }
}
