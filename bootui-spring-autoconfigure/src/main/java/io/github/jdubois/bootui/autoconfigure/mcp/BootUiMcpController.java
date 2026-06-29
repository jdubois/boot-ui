package io.github.jdubois.bootui.autoconfigure.mcp;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loopback HTTP transport for the BootUI MCP server (MCP "Streamable HTTP" style).
 *
 * <p>Clients POST JSON-RPC 2.0 requests to {@code /bootui/api/mcp} and receive JSON-RPC responses.
 * The endpoint lives under {@code /bootui/api} so it inherits {@code LocalhostOnlyFilter}'s loopback,
 * Host allow-list, and cross-site write defenses. The beans are always registered while BootUI is
 * active (dev contexts), but requests are only served while the server is enabled — the live state
 * is initialized from {@code bootui.mcp.enabled} and can be toggled at runtime from the MCP Server
 * panel via {@link McpServerState}.
 *
 * <p>The {@code GET} variant returns a small human-readable status document (tool count, endpoint,
 * enabled state) so the server can be sanity-checked with a browser or {@code curl} on the loopback
 * interface.
 */
@RestController
@RequestMapping("/bootui/api/mcp")
public class BootUiMcpController {

    private static final int SERVER_DISABLED = -32000;

    private final BootUiMcpService service;
    private final BootUiMcpTools tools;
    private final McpServerState state;

    public BootUiMcpController(BootUiMcpService service, BootUiMcpTools tools, McpServerState state) {
        this.service = service;
        this.tools = tools;
        this.state = state;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> rpc(@RequestBody JsonNode request) {
        if (!state.isEnabled()) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(disabledError(request));
        }
        if (request != null && request.isArray()) {
            ArrayNode responses = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : request) {
                JsonNode response = service.handle(element);
                if (response != null) {
                    responses.add(response);
                }
            }
            if (responses.isEmpty()) {
                return ResponseEntity.accepted().build();
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(responses);
        }

        JsonNode response = service.handle(request);
        if (response == null) {
            // Notification (no id) — acknowledge with 202 and no body.
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    @GetMapping
    public ResponseEntity<JsonNode> status() {
        ObjectNode status = JsonNodeFactory.instance.objectNode();
        status.put("server", BootUiMcpService.SERVER_NAME);
        status.put("enabled", state.isEnabled());
        status.put("transport", "http");
        status.put("endpoint", "/bootui/api/mcp");
        status.put("protocolVersion", BootUiMcpService.DEFAULT_PROTOCOL_VERSION);
        status.put("toolCount", tools.tools().size());
        ArrayNode toolNames = JsonNodeFactory.instance.arrayNode();
        tools.tools().forEach(tool -> toolNames.add(tool.name()));
        status.set("tools", toolNames);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(status);
    }

    /**
     * Builds a JSON-RPC error response indicating the server is disabled, preserving the request id
     * when present so a compliant client can correlate it.
     */
    private static JsonNode disabledError(JsonNode request) {
        JsonNode id = request != null && request.isObject() ? request.get("id") : null;
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", SERVER_DISABLED);
        error.put(
                "message",
                "BootUI MCP server is disabled. Enable it from the MCP Server panel or set bootui.mcp.enabled=ON.");
        response.set("error", error);
        return response;
    }
}
