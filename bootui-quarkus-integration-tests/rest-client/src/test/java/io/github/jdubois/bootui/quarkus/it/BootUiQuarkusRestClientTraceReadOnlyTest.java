package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.Map;
import org.acme.restclient.PingClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(RestClientTraceReadOnlyProfile.class)
class BootUiQuarkusRestClientTraceReadOnlyTest {

    @TestHTTPResource
    URL baseUrl;

    @Inject
    @RestClient
    PingClient pingClient;

    @Test
    void perPanelReadOnlyGateBlocksClearAndRecordingButKeepsReadsAvailable() {
        assertThat(pingClient.ping(1)).isEqualTo("pong-1");
        Response report = probe().get("/bootui/api/rest-client-trace");
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.json().path("available").asBoolean(false)).isTrue();

        JsonNode panel = findPanel(probe().get("/bootui/api/panels").json());
        assertThat(panel.path("readOnly").asBoolean(false)).isTrue();
        assertThat(panel.path("readOnlyReason").asText()).contains("rest-client-trace.read-only=true");

        Map<String, String> headers = Map.of("Content-Type", "application/json");
        Response clear = probe().request("POST", "/bootui/api/rest-client-trace/clear", headers, "{}");
        assertThat(clear.status()).isEqualTo(403);
        assertThat(clear.body()).contains("\"panel\":\"rest-client-trace\"", "Panel is read-only");

        Response recording =
                probe().request("POST", "/bootui/api/rest-client-trace/recording", headers, "{\"enabled\":false}");
        assertThat(recording.status()).isEqualTo(403);
        assertThat(recording.body()).contains("\"panel\":\"rest-client-trace\"", "Panel is read-only");
    }

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    private static JsonNode findPanel(JsonNode report) {
        for (JsonNode panel : report.path("panels")) {
            if ("rest-client-trace".equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        throw new AssertionError("REST Client panel missing from manifest");
    }
}
