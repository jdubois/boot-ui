package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Shared black-box HTTP conformance contract for the BootUI command-line endpoint at
 * {@code /bootui/api/cli}.
 *
 * <p>The whole point of this endpoint is that a {@code bootui} CLI built once works against any BootUI
 * instance, whichever stack it runs on. These tests are what makes that true: they pin the catalog shape,
 * the argument handling, and — most importantly — the HTTP status each refusal produces, since a shell
 * branches on a status code rather than on a message.
 *
 * <p>Every case here goes through the same {@code McpDispatcher} logic MCP uses, so a divergence in panel
 * policy between the two surfaces would show up as a failure here.
 */
public abstract class AbstractCliConformanceTest {

    private static final String CLI = "/bootui/api/cli";

    protected abstract String baseUrl();

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl());
    }

    private Response invoke(String tool, String body) {
        return probe().request("POST", CLI + "/tools/" + tool, Map.of("Content-Type", "application/json"), body);
    }

    private JsonNode catalogEntry(String tool) {
        for (JsonNode entry : probe().get(CLI).json().path("tools")) {
            if (tool.equals(entry.path("name").asText())) {
                return entry;
            }
        }
        throw new AssertionError("Tool " + tool + " is not advertised by this instance");
    }

    @Test
    void testCliCatalogDescribesTheToolsThisInstanceExposes() {
        Response response = probe().get(CLI);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        JsonNode body = response.json();
        assertThat(body.path("enabled").asBoolean()).isTrue();
        assertThat(body.path("serverName").asText()).isEqualTo("bootui");
        assertThat(body.path("endpoint").asText()).isEqualTo(CLI);
        assertThat(body.path("maxResults").asInt()).isPositive();
        assertThat(body.path("tools").isArray()).isTrue();
        assertThat(body.path("tools")).isNotEmpty();
        assertThat(body.path("toolCount").asInt()).isEqualTo(body.path("tools").size());

        JsonNode tool = body.path("tools").get(0);
        assertThat(tool.path("name").asText()).isNotBlank();
        assertThat(tool.path("description").asText()).isNotBlank();
        assertThat(tool.path("panel").asText()).isNotBlank();
        assertThat(tool.path("schema").asText()).isIn("NONE", "LIMIT", "QUERY_LIMIT", "ID");
        assertThat(tool.path("arguments").isArray()).isTrue();
        assertThat(tool.path("action").isBoolean()).isTrue();
        assertThat(tool.path("panelEnabled").isBoolean()).isTrue();
        assertThat(tool.path("panelReadOnly").isBoolean()).isTrue();
    }

    @Test
    void testCliCatalogIsServedWithoutEnablingTheMcpServer() {
        // The MCP server is off in every conformance profile. If the catalog still answers, the CLI does not
        // inherit the MCP toggle as a prerequisite — the reason this endpoint exists at all.
        Response mcp = probe().request(
                        "POST",
                        "/bootui/api/mcp",
                        Map.of("Content-Type", "application/json"),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertThat(mcp.json().path("error").path("code").asInt()).isEqualTo(-32000);

        assertThat(probe().get(CLI).status()).isEqualTo(200);
        assertThat(invoke("get_overview", "{}").status()).isEqualTo(200);
    }

    @Test
    void testExplorerReadToolsMatchRuntimeAvailabilityAndRequireCanonicalId() {
        boolean available = false;
        for (JsonNode panel : probe().get("/bootui/api/panels").json().path("panels")) {
            if ("explorer".equals(panel.path("id").asText())) {
                available = panel.path("available").asBoolean();
            }
        }
        java.util.List<String> advertised = new java.util.ArrayList<>();
        probe().get(CLI)
                .json()
                .path("tools")
                .forEach(tool -> advertised.add(tool.path("name").asText()));
        assertThat(advertised.contains("get_explorer")).isEqualTo(available);
        assertThat(advertised.contains("get_explorer_event")).isEqualTo(available);
        if (available) {
            assertThat(catalogEntry("get_explorer").path("schema").asText()).isEqualTo("LIMIT");
            assertThat(catalogEntry("get_explorer_event").path("schema").asText())
                    .isEqualTo("ID");
            assertThat(catalogEntry("get_explorer").path("action").asBoolean()).isFalse();
            assertThat(catalogEntry("get_explorer_event").path("action").asBoolean())
                    .isFalse();
            Response response = invoke("get_explorer", "{\"limit\":2}");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().path("activity").path("entries").isArray())
                    .isTrue();
            assertThat(response.json().path("activity").path("entries").size()).isLessThanOrEqualTo(2);
            assertThat(invoke("get_explorer_event", "{}").status()).isEqualTo(400);
        }
    }

    @Test
    void testCliReadToolReturnsThePayloadWithoutAJsonRpcEnvelope() {
        Response response = invoke("get_overview", "{}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        assertThat(response.json().isObject()).isTrue();
        assertThat(response.json().has("jsonrpc")).isFalse();
        assertThat(response.json().has("result")).isFalse();
    }

    @Test
    void testCliReadToolAcceptsAnEmptyBody() {
        Response response = probe().request("POST", CLI + "/tools/get_overview", Map.of(), null);

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void testCliSearchToolAcceptsQueryAndLimit() {
        Response response = invoke("get_config", "{\"query\":\"spring\",\"limit\":5}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
    }

    @Test
    void testCliUnknownToolIsNotFound() {
        Response response = invoke("no_such_tool", "{}");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().path("error").asText()).contains("no_such_tool");
    }

    @Test
    void testCliArgumentTheToolDoesNotDeclareIsRejected() {
        Response response = invoke("get_overview", "{\"id\":\"anything\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText()).isNotBlank();
    }

    @Test
    void testCliPropertyOutsideEveryToolSchemaIsRejectedRatherThanIgnored() {
        // A misspelled filter must not come back as a successful, unfiltered report: the body reaches the
        // dispatcher verbatim, so binding cannot quietly drop what the tool never declared.
        Response response = invoke("get_config", "{\"q\":\"spring\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText()).contains("q");
    }

    @Test
    void testCliArgumentOfTheWrongTypeIsRejected() {
        Response response = invoke("get_config", "{\"limit\":\"ten\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText()).isNotBlank();
    }

    @Test
    void testCliMissingRequiredIdIsRejected() {
        Response response = invoke("get_exception_detail", "{}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText()).isNotBlank();
    }

    @Test
    void testCliDisabledPanelRefusesItsTools() {
        // Every conformance profile disables the Memory panel. It is deliberately a panel every stack
        // reports as available on any machine: a panel that is *unavailable* drops its tools from the
        // registry entirely, so the refusal under test would be indistinguishable from an unknown tool.
        assertThat(catalogEntry("get_memory_report").path("panelEnabled").asBoolean())
                .as("the profile must disable a panel this instance actually advertises")
                .isFalse();

        Response response = invoke("get_memory_report", "{}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().path("error").asText()).isNotBlank();
    }

    @Test
    void testCliReadOnlyPanelRefusesActionsButStillServesReads() {
        // Every conformance profile marks the Heap Dump panel read-only.
        assertThat(invoke("analyze_heap_dump", "{}").status()).isEqualTo(403);
        assertThat(invoke("get_heap_dump_report", "{}").status()).isEqualTo(200);
    }

    @Test
    void testCliGetRejectsToolInvocation() {
        Response response = probe().get(CLI + "/tools/get_overview");

        assertThat(response.status()).isIn(404, 405);
    }
}
