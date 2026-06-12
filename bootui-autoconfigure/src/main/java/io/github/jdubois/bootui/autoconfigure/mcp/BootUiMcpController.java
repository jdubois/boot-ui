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
 * Host allow-list, and cross-site write defenses; it is only registered when
 * {@code bootui.mcp.enabled=ON} and BootUI itself is active (dev contexts).
 *
 * <p>The {@code GET} variant returns a small human-readable status document (tool count, endpoint,
 * enabled state) so the server can be sanity-checked with a browser or {@code curl} on the loopback
 * interface.
 */
@RestController
@RequestMapping("/bootui/api/mcp")
public class BootUiMcpController {

    private final BootUiMcpService service;
    private final BootUiMcpTools tools;

    public BootUiMcpController(BootUiMcpService service, BootUiMcpTools tools) {
        this.service = service;
        this.tools = tools;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> rpc(@RequestBody JsonNode request) {
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
        status.put("enabled", true);
        status.put("transport", "http");
        status.put("endpoint", "/bootui/api/mcp");
        status.put("protocolVersion", BootUiMcpService.DEFAULT_PROTOCOL_VERSION);
        status.put("toolCount", tools.tools().size());
        ArrayNode toolNames = JsonNodeFactory.instance.arrayNode();
        tools.tools().forEach(tool -> toolNames.add(tool.name()));
        status.set("tools", toolNames);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(status);
    }
}
