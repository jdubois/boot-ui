package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Implements the subset of the Model Context Protocol that BootUI needs to expose its advisors and
 * diagnostics as tools to a local AI agent.
 *
 * <p>This is a transport-agnostic JSON-RPC 2.0 handler. {@link BootUiMcpController} adapts it to a
 * single loopback HTTP endpoint; the handler itself only understands JSON-RPC messages. Supported
 * methods: {@code initialize}, {@code notifications/initialized} (notification), {@code ping},
 * {@code tools/list}, and {@code tools/call}.
 *
 * <p>Safety: every {@code tools/call} is gated by the same per-panel toggles the browser UI obeys —
 * a tool whose backing panel is disabled ({@code bootui.panels.<id>.enabled=false}) is refused, and
 * an action tool whose panel is read-only ({@code bootui.panels.<id>.read-only=true} or the global
 * {@code bootui.read-only=true}) is refused for the same reasons {@code PanelAccessFilter} blocks a
 * state-changing HTTP request. The endpoint also inherits the loopback/Host/cross-site defenses of
 * {@code LocalhostOnlyFilter} because it lives under {@code /bootui/api}.
 */
public class BootUiMcpService {

    /** MCP protocol revision advertised when the client does not request a specific one. */
    static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    static final String SERVER_NAME = "bootui";

    private static final Logger log = LoggerFactory.getLogger(BootUiMcpService.class);

    // JSON-RPC 2.0 error codes.
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final BootUiMcpTools tools;
    private final BootUiProperties properties;
    private final ObjectMapper objectMapper;
    private final String serverVersion;

    public BootUiMcpService(
            BootUiMcpTools tools, BootUiProperties properties, ObjectMapper objectMapper, String serverVersion) {
        this.tools = tools;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.serverVersion = serverVersion == null ? "dev" : serverVersion;
    }

    /**
     * Handles a single JSON-RPC request or notification.
     *
     * @return the JSON-RPC response, or {@code null} for notifications (which have no response)
     */
    public JsonNode handle(JsonNode request) {
        if (request == null || !request.isObject()) {
            return error(null, INVALID_PARAMS, "Request must be a JSON-RPC object");
        }
        String method = request.path("method").asString();
        JsonNode id = request.get("id");
        boolean isNotification = id == null || id.isNull();

        if (method == null || method.isEmpty()) {
            return isNotification ? null : error(id, INVALID_PARAMS, "Missing 'method'");
        }

        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(request.path("params")));
                case "ping" -> result(id, JsonNodeFactory.instance.objectNode());
                case "tools/list" -> result(id, listTools());
                case "tools/call" -> result(id, callTool(request.path("params")));
                default -> {
                    if (isNotification) {
                        // notifications/initialized and other one-way messages need no response.
                        yield null;
                    }
                    yield error(id, METHOD_NOT_FOUND, "Unknown method: " + method);
                }
            };
        } catch (McpToolException ex) {
            // Tool-level failures are reported in-band so the agent can read the reason.
            return result(id, toolError(ex.getMessage()));
        } catch (RuntimeException ex) {
            log.debug("BootUI MCP request failed", ex);
            return error(id, INTERNAL_ERROR, ex.getMessage());
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asString();
        String protocolVersion = (requested == null || requested.isEmpty()) ? DEFAULT_PROTOCOL_VERSION : requested;

        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("protocolVersion", protocolVersion);

        ObjectNode capabilities = JsonNodeFactory.instance.objectNode();
        ObjectNode toolsCapability = JsonNodeFactory.instance.objectNode();
        toolsCapability.put("listChanged", false);
        capabilities.set("tools", toolsCapability);
        response.set("capabilities", capabilities);

        ObjectNode serverInfo = JsonNodeFactory.instance.objectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", serverVersion);
        response.set("serverInfo", serverInfo);

        response.put(
                "instructions",
                "BootUI exposes a running Spring Boot application. Call the *_scan advisor tools to get "
                        + "actionable findings to fix, and the get_* tools (exceptions, security logs, SQL traces, "
                        + "traces, HTTP exchanges, config, beans, mappings) to understand runtime behavior. All data "
                        + "is read locally and secret values are masked.");
        return response;
    }

    private ObjectNode listTools() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (McpTool tool : tools.tools()) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("inputSchema", tool.inputSchema());
            array.add(node);
        }
        result.set("tools", array);
        return result;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asString();
        if (name == null || name.isEmpty()) {
            throw new McpToolException("Missing tool name");
        }
        McpTool tool = findTool(name).orElseThrow(() -> new McpToolException("Unknown tool: " + name));

        if (!properties.isPanelEnabled(tool.panelId())) {
            throw new McpToolException(properties.panelDisabledReason(tool.panelId()));
        }
        if (tool.action() && properties.isPanelReadOnly(tool.panelId())) {
            throw new McpToolException(properties.panelReadOnlyReason(tool.panelId()));
        }

        JsonNode arguments = params.get("arguments");
        Object payload = tool.invoke(arguments == null ? JsonNodeFactory.instance.objectNode() : arguments);
        String text = objectMapper.writeValueAsString(payload);

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode content = JsonNodeFactory.instance.arrayNode();
        ObjectNode textContent = JsonNodeFactory.instance.objectNode();
        textContent.put("type", "text");
        textContent.put("text", text);
        content.add(textContent);
        result.set("content", content);
        result.put("isError", false);
        return result;
    }

    private Optional<McpTool> findTool(String name) {
        return tools.tools().stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    private static ObjectNode toolError(String message) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode content = JsonNodeFactory.instance.arrayNode();
        ObjectNode textContent = JsonNodeFactory.instance.objectNode();
        textContent.put("type", "text");
        textContent.put("text", message == null ? "Tool call failed" : message);
        content.add(textContent);
        result.set("content", content);
        result.put("isError", true);
        return result;
    }

    private static ObjectNode result(JsonNode id, JsonNode payload) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", normalizeId(id));
        response.set("result", payload);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", normalizeId(id));
        ObjectNode err = JsonNodeFactory.instance.objectNode();
        err.put("code", code);
        err.put("message", message == null ? "Error" : message);
        response.set("error", err);
        return response;
    }

    private static JsonNode normalizeId(JsonNode id) {
        return id == null ? JsonNodeFactory.instance.nullNode() : id;
    }

    /** Signals a tool-level failure that should be reported in-band ({@code isError: true}). */
    static final class McpToolException extends RuntimeException {
        McpToolException(String message) {
            super(message);
        }
    }
}
