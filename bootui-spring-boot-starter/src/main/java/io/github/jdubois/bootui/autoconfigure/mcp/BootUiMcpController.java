package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader.PayloadTooLargeException;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
 * <p>The endpoint is dual-era: {@link BootUiMcpService#exchange} selects MCP 2025-06-18 or MCP 2026-07-28 from the
 * request body and its {@code MCP-Protocol-Version}, {@code Mcp-Method}, and {@code Mcp-Name} headers, so the body is
 * read before any header is judged. The {@code GET} variant returns 405 because BootUI offers no server-to-client
 * stream at this endpoint, which MCP 2026-07-28 removed altogether. Human-readable status is available from
 * {@code /bootui/api/mcp-server}.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/mcp")
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
    public ResponseEntity<String> rpc(HttpServletRequest servletRequest, @RequestHeader HttpHeaders headers) {
        byte[] requestBody;
        try {
            requestBody = McpPayloadReader.read(servletRequest.getInputStream(), maxPayloadBytes);
        } catch (PayloadTooLargeException ex) {
            return json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE));
        } catch (IllegalArgumentException | java.io.IOException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, "Could not read request payload"));
        }
        JsonNode request;
        try {
            request = service.readTree(requestBody);
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        BootUiMcpService.Reply reply = service.exchange(request, BootUiMcpService.headers(headers), state.isEnabled());
        if (reply.body() == null) {
            // Notification (no id) — acknowledge with 202 and no body.
            return ResponseEntity.accepted().build();
        }
        return json(reply.status(), reply.body());
    }

    @GetMapping
    public ResponseEntity<Void> getStream() {
        return ResponseEntity.status(405).build();
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
