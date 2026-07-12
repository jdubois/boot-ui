package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

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
 * <p>The {@code GET} variant returns 405 because BootUI does not offer a server-to-client SSE stream
 * at this endpoint. Human-readable status is available from {@code /bootui/api/mcp-server}.
 */
@RestController
@RequestMapping("/bootui/api/mcp")
public class BootUiMcpController {

    private static final String PAYLOAD_LIMIT_MESSAGE = "Request payload exceeds limit";

    private final BootUiMcpService service;
    private final McpServerState state;
    private final int maxPayloadBytes;

    public BootUiMcpController(BootUiMcpService service, McpServerState state, BootUiProperties properties) {
        this.service = service;
        this.state = state;
        this.maxPayloadBytes = Math.max(1, properties.getMcp().getMaxPayloadBytes());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rpc(
            @RequestBody byte[] requestBody,
            @RequestHeader(value = McpProtocol.PROTOCOL_VERSION_HEADER, required = false) String protocolVersion) {
        if (protocolVersion != null && !McpProtocol.KNOWN_VERSIONS.contains(protocolVersion)) {
            return json(
                    400, error(null, McpProtocol.INVALID_REQUEST, McpProtocol.UNSUPPORTED_PROTOCOL_VERSION_MESSAGE));
        }
        if (requestBody != null && requestBody.length > maxPayloadBytes) {
            return json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE));
        }
        JsonNode request;
        try {
            request = service.readTree(requestBody == null ? new byte[0] : requestBody);
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        if (request != null && request.isArray()) {
            return json(400, error(null, McpProtocol.INVALID_REQUEST, McpProtocol.BATCH_NOT_SUPPORTED_MESSAGE));
        }
        if (!state.isEnabled()) {
            if (isNotification(request)) {
                return ResponseEntity.accepted().build();
            }
            return json(200, disabledError(request));
        }
        JsonNode response = service.handle(request);
        if (response == null) {
            // Notification (no id) — acknowledge with 202 and no body.
            return ResponseEntity.accepted().build();
        }
        return json(200, response);
    }

    @GetMapping
    public ResponseEntity<Void> getStream() {
        return ResponseEntity.status(405).build();
    }

    /**
     * Builds a JSON-RPC error response indicating the server is disabled, preserving the request id
     * when present so a compliant client can correlate it.
     */
    private static JsonNode disabledError(JsonNode request) {
        JsonNode id = request != null && request.isObject() ? request.get("id") : null;
        return error(id, McpProtocol.SERVER_DISABLED, McpProtocol.SERVER_DISABLED_MESSAGE);
    }

    private static boolean isNotification(JsonNode request) {
        return request != null
                && request.isObject()
                && !request.hasNonNull("id")
                && McpProtocol.JSONRPC_VERSION.equals(request.path("jsonrpc").asString())
                && !request.path("method").asString().isBlank();
    }

    private static tools.jackson.databind.node.ObjectNode error(JsonNode id, int code, String message) {
        tools.jackson.databind.node.ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        tools.jackson.databind.node.ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", code);
        error.put("message", message == null ? "Error" : message);
        response.set("error", error);
        return response;
    }

    private static ResponseEntity<String> json(int status, JsonNode body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString());
    }
}
