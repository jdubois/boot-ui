package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot, end-to-end proof that a disabled panel's MCP tool is refused through the <em>full</em>
 * production wiring: the JSON-RPC bridge ({@code McpBridgeResource}) → the shared engine
 * {@code McpDispatcher} → {@code QuarkusMcpPanelPolicy} → the same {@code QuarkusPanelAccessConfig} that
 * backs {@link io.github.jdubois.bootui.quarkus.QuarkusPanelAccessFilter}. The policy's delegation logic in
 * isolation is unit-tested by {@code QuarkusMcpPanelPolicyTest} (5 scenarios: default-enabled, disabled,
 * default-not-read-only, per-panel read-only, global read-only); this class instead pins that
 * {@code BootUiMcpProducer} actually wires the real config-backed policy into the dispatcher end-to-end,
 * mirroring the existing {@link BootUiQuarkusMcpResourceTest} real-HTTP conventions.
 */
@QuarkusTest
@TestProfile(QuarkusMcpPanelPolicyBootTest.DisabledMemoryPanelProfile.class)
class QuarkusMcpPanelPolicyBootTest {

    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    private void setServerEnabled(boolean enabled) {
        Response response =
                probe().request("POST", "/bootui/api/mcp-server/toggle", JSON, "{\"enabled\":" + enabled + "}");
        assertThat(response.status()).isEqualTo(200);
    }

    private Response rpc(String body) {
        return probe().request("POST", "/bootui/api/mcp", JSON, body);
    }

    @Test
    void disabledPanelToolCallIsRefusedThroughTheFullMcpStack() {
        setServerEnabled(true);
        Response response =
                rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"memory_scan\"}}");

        assertThat(response.status()).isEqualTo(200);
        JsonNode result = response.json().path("result");
        assertThat(result.path("isError").asBoolean())
                .as("a disabled panel's tool call must be refused")
                .isTrue();
        assertThat(result.path("content").get(0).path("text").asText())
                .isEqualTo("Panel is disabled via bootui.panels.memory.enabled=false");
    }

    @Test
    void unaffectedToolIsStillCallable() {
        // Sanity check: disabling one panel (memory) must not affect an unrelated tool.
        setServerEnabled(true);
        Response response =
                rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_health\"}}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("result").path("isError").asBoolean()).isFalse();
    }

    public static final class DisabledMemoryPanelProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.panels.memory.enabled", "false");
        }
    }
}
