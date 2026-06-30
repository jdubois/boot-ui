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
 * Real-boot checks for the BootUI MCP server bridge on Quarkus: the JSON-RPC transport
 * ({@code McpBridgeResource} at {@code /bootui/api/mcp}) and the panel toggle
 * ({@code McpServerResource} at {@code /bootui/api/mcp-server}), both over the shared framework- and
 * JSON-free engine {@code McpDispatcher}.
 *
 * <p>The server is disabled by default ({@code bootui.mcp.enabled} defaults to {@code OFF}); each test
 * sets its own precondition via {@code POST /mcp-server/toggle} so the cases stay order-independent
 * even though {@code McpServerState} is a runtime singleton shared across the {@code @QuarkusTest}
 * application instance.
 */
@QuarkusTest
class BootUiQuarkusMcpResourceTest {

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
        assertThat(response.json().path("enabled").asBoolean()).isEqualTo(enabled);
    }

    private Response rpc(String body) {
        return probe().request("POST", "/bootui/api/mcp", JSON, body);
    }

    @Test
    void toggleFlipsEnabledState() {
        setServerEnabled(true);
        assertThat(probe().get("/bootui/api/mcp-server").json().path("enabled").asBoolean())
                .isTrue();
        setServerEnabled(false);
        assertThat(probe().get("/bootui/api/mcp-server").json().path("enabled").asBoolean())
                .isFalse();
    }

    @Test
    void requestWhileDisabledReturnsServerDisabledError() {
        setServerEnabled(false);
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32000);
        assertThat(body.path("id").asInt()).isEqualTo(1);
    }

    @Test
    void initializeReturnsServerInfo() {
        setServerEnabled(true);
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
        assertThat(response.status()).isEqualTo(200);
        JsonNode result = response.json().path("result");
        assertThat(result.path("protocolVersion").asText()).isNotBlank();
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("bootui");
        assertThat(result.path("capabilities").path("tools").isObject()).isTrue();
        assertThat(result.path("instructions").asText()).contains("Quarkus");
    }

    @Test
    void toolsListAdvertisesQuarkusCatalog() {
        setServerEnabled(true);
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
        assertThat(response.status()).isEqualTo(200);
        JsonNode tools = response.json().path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools).isNotEmpty();

        java.util.Set<String> names = new java.util.HashSet<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        // Always-available Quarkus tool is advertised...
        assertThat(names).contains("get_health");
        // ...and the Spring-only tools are deliberately absent on Quarkus.
        assertThat(names).doesNotContain("get_overview", "graalvm_scan", "crac_scan");
    }

    @Test
    void toolCallReturnsContent() {
        setServerEnabled(true);
        Response response = rpc(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"get_health\"}}");
        assertThat(response.status()).isEqualTo(200);
        JsonNode result = response.json().path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(result.path("content").get(0).path("text").asText()).isNotBlank();
    }

    @Test
    void notificationReturns202() {
        setServerEnabled(true);
        // A JSON-RPC notification (no id) to a method with no response — the canonical "initialized"
        // notification a client sends after initialize — is acknowledged with 202 and no body.
        Response response = rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(response.status()).isEqualTo(202);
        assertThat(response.body()).isEmpty();
    }
}
