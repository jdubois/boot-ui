package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class BootUiMcpServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BootUiProperties properties;
    private BootUiMcpService service;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        List<McpTool> tools = List.of(
                new McpTool(
                        "get_overview",
                        "Read the overview.",
                        schema(),
                        BootUiPanels.OVERVIEW,
                        false,
                        args -> java.util.Map.of("name", "demo")),
                new McpTool(
                        "architecture_scan",
                        "Run the architecture advisor.",
                        schema(),
                        BootUiPanels.ARCHITECTURE,
                        true,
                        args -> java.util.Map.of("findings", List.of())));
        service = new BootUiMcpService(new BootUiMcpTools(tools), properties, objectMapper, "1.2.3");
    }

    @Test
    void initializeReturnsServerInfoAndEchoesProtocolVersion() {
        JsonNode response = service.handle(request("initialize", 1, params("protocolVersion", "2025-06-18")));

        assertThat(response.path("result").path("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(response.path("result").path("serverInfo").path("name").asString())
                .isEqualTo("bootui");
        assertThat(response.path("result").path("serverInfo").path("version").asString())
                .isEqualTo("1.2.3");
        assertThat(response.path("result").path("capabilities").has("tools")).isTrue();
        assertThat(response.path("result").path("capabilities").has("prompts")).isTrue();
        assertThat(response.path("result").path("instructions").asString())
                .contains("get_overview", "do not modify code blindly", "may still contain sensitive data");
    }

    @Test
    void initializeFallsBackToDefaultProtocolVersion() {
        JsonNode response = service.handle(request("initialize", 1, JsonNodeFactory.instance.objectNode()));

        assertThat(response.path("result").path("protocolVersion").asString())
                .isEqualTo(BootUiMcpService.DEFAULT_PROTOCOL_VERSION);
    }

    @Test
    void toolsListAdvertisesEveryTool() {
        JsonNode response = service.handle(request("tools/list", 2, null));

        JsonNode toolsArray = response.path("result").path("tools");
        assertThat(toolsArray).hasSize(2);
        assertThat(toolsArray.get(0).path("name").asString()).isEqualTo("get_overview");
        assertThat(toolsArray.get(0).path("inputSchema").path("type").asString())
                .isEqualTo("object");
        assertThat(toolsArray.get(0).path("outputSchema").path("description").asString())
                .contains("get_overview");
    }

    @Test
    void promptsListAndGetExposeDiagnosticWorkflows() {
        JsonNode list = service.handle(request("prompts/list", 3, null));

        JsonNode prompts = list.path("result").path("prompts");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0).path("name").asString()).isEqualTo("diagnose_runtime_issue");
        assertThat(prompts.get(0).path("arguments").isArray()).isTrue();
        assertThat(prompts.get(0).path("arguments")).hasSize(0);

        JsonNode prompt = service.handle(request("prompts/get", 4, params("name", "diagnose_runtime_issue")));
        assertThat(prompt.path("result").path("messages").get(0).path("role").asString())
                .isEqualTo("user");
        assertThat(prompt.path("result")
                        .path("messages")
                        .get(0)
                        .path("content")
                        .path("text")
                        .asString())
                .contains("get_live_activity", "Separate observed evidence from hypotheses");
    }

    @Test
    void toolsCallReturnsSerializedDtoContent() {
        JsonNode response = service.handle(callRequest("get_overview", 3));

        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        String text = result.path("content").get(0).path("text").asString();
        assertThat(text).contains("\"name\":\"demo\"");
    }

    @Test
    void toolsCallOnDisabledPanelReturnsInBandError() {
        properties.panel(BootUiPanels.OVERVIEW).setEnabled(false);

        JsonNode response = service.handle(callRequest("get_overview", 4));

        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asString())
                .contains("bootui.panels.overview.enabled=false");
    }

    @Test
    void actionToolOnReadOnlyPanelIsRefused() {
        properties.panel(BootUiPanels.ARCHITECTURE).setReadOnly(true);

        JsonNode response = service.handle(callRequest("architecture_scan", 5));

        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asString()).contains("read-only");
    }

    @Test
    void readToolOnReadOnlyPanelStillSucceeds() {
        properties.panel(BootUiPanels.OVERVIEW).setReadOnly(true);

        JsonNode response = service.handle(callRequest("get_overview", 6));

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
    }

    @Test
    void unknownToolReturnsInvalidParamsError() {
        JsonNode response = service.handle(callRequest("does_not_exist", 7));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asString()).contains("Unknown tool");
    }

    @Test
    void unknownMethodReturnsJsonRpcMethodNotFound() {
        JsonNode response = service.handle(request("tools/unknown", 8, null));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void notificationsProduceNoResponse() {
        JsonNode notification = request("notifications/initialized", null, null);

        assertThat(service.handle(notification)).isNull();
    }

    @Test
    void toolHandlerExceptionWithoutMessageReportsGenericErrorMessage() {
        List<McpTool> tools = List.of(new McpTool(
                "get_overview", "Read the overview.", McpToolSchema.NONE, BootUiPanels.OVERVIEW, false, args -> {
                    throw new IllegalStateException();
                }));
        BootUiMcpService failing = new BootUiMcpService(new BootUiMcpTools(tools), properties, objectMapper, "1.2.3");

        JsonNode response = failing.handle(callRequest("get_overview", 9));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(response.path("error").path("message").asString()).isEqualTo("Error");
    }

    @Test
    void toolResultSerializationFailureReturnsInternalError() {
        List<McpTool> tools = List.of(new McpTool(
                "get_overview",
                "Read the overview.",
                McpToolSchema.NONE,
                BootUiPanels.OVERVIEW,
                false,
                args -> new Object() {
                    @SuppressWarnings("unused")
                    public String getValue() {
                        throw new IllegalStateException("cannot serialize");
                    }
                }));
        BootUiMcpService failing = new BootUiMcpService(new BootUiMcpTools(tools), properties, objectMapper, "1.2.3");

        JsonNode response = failing.handle(callRequest("get_overview", 10));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32603);
    }

    private static McpToolSchema schema() {
        return McpToolSchema.NONE;
    }

    private ObjectNode callRequest(String toolName, int id) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", toolName);
        params.set("arguments", JsonNodeFactory.instance.objectNode());
        return request("tools/call", id, params);
    }

    private ObjectNode params(String key, String value) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put(key, value);
        return params;
    }

    private ObjectNode request(String method, Integer id, JsonNode params) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        if (id != null) {
            request.put("id", id);
        }
        if (params != null) {
            request.set("params", params);
        }
        return request;
    }
}
