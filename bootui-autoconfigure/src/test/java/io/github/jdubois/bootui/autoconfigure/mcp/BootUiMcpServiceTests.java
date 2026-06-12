package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
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
    void unknownToolReturnsInBandError() {
        JsonNode response = service.handle(callRequest("does_not_exist", 7));

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asString())
                .contains("Unknown tool");
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

    private static ObjectNode schema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.set("properties", JsonNodeFactory.instance.objectNode());
        return schema;
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
