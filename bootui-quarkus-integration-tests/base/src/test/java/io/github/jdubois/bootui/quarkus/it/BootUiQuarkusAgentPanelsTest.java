package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the three Developer-Tools panels lit up on Quarkus: Copilot
 * ({@code CopilotResource}), Claude Code ({@code ClaudeCodeResource}) and MCP Server
 * ({@code McpServerResource}). Copilot/Claude reuse the shared engine {@code AgentSessionStore}; this
 * pins that the GET endpoints render JSON (empty when no local CLI sessions exist) and that the
 * MCP panel reports a live status (HTTP transport + a non-empty tool catalog) — the full JSON-RPC
 * bridge is exercised separately by {@code BootUiQuarkusMcpResourceTest}.
 */
@QuarkusTest
class BootUiQuarkusAgentPanelsTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void copilotDashboardRenders() {
        Response response = probe().get("/bootui/api/copilot/dashboard");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        assertThat(response.json().has("sessionCount")).isTrue();
    }

    @Test
    void copilotSessionsRenders() {
        Response response = probe().get("/bootui/api/copilot/sessions");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("sessions").isArray()).isTrue();
    }

    @Test
    void claudeCodeDashboardRenders() {
        Response response = probe().get("/bootui/api/claude-code/dashboard");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().has("sessionCount")).isTrue();
    }

    @Test
    void mcpServerReportsLiveStatus() {
        Response response = probe().get("/bootui/api/mcp-server");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        JsonNode body = response.json();
        // Disabled by default (bootui.mcp.enabled defaults to OFF) but the bridge is fully wired.
        assertThat(body.path("enabled").asBoolean()).isFalse();
        assertThat(body.path("transport").asText()).isEqualTo("http");
        assertThat(body.path("endpoint").asText()).isEqualTo("/bootui/api/mcp");
        assertThat(body.path("toolCount").asInt()).isPositive();
        assertThat(body.path("tools").isArray()).isTrue();
        assertThat(body.path("tools")).isNotEmpty();
    }

    @Test
    void mcpServerPanelAdvertisedAvailable() {
        JsonNode panels = probe().get("/bootui/api/panels").json().path("panels");
        boolean mcpAvailable = false;
        for (JsonNode panel : panels) {
            if ("mcp-server".equals(panel.path("id").asText())) {
                mcpAvailable = panel.path("available").asBoolean();
            }
        }
        assertThat(mcpAvailable).isTrue();
    }
}
