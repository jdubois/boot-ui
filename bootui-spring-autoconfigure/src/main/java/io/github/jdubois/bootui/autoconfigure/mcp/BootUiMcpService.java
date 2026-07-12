package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiMcpTools;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.InitializeResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.NoResponse;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PingResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptGetResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptsListResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolsListResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpGuidance;
import io.github.jdubois.bootui.engine.mcp.McpPrompt;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequest;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptor;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spring Boot (Jackson 3) JSON-RPC envelope codec for the BootUI MCP server.
 *
 * <p>This is a transport-agnostic JSON-RPC 2.0 handler. {@link BootUiMcpController} adapts it to a
 * single loopback HTTP endpoint; the handler itself only understands JSON-RPC messages. Supported
 * methods: {@code initialize}, {@code notifications/initialized} (notification), {@code ping},
 * {@code tools/list}, {@code tools/call}, {@code prompts/list}, and {@code prompts/get}.
 *
 * <p>The protocol decisions (method routing, per-panel gating, tool lookup, argument capping, error
 * codes and canonical messages) live in the framework- and JSON-free engine {@link McpDispatcher};
 * this class only does the irreducibly-Jackson part — parse a request node into a neutral
 * {@link McpRequest}, dispatch it, and render the {@link McpDispatchOutcome} back to JSON (echoing the
 * id, building each tool's input schema, and serializing the tool payload with its own
 * {@link ObjectMapper}). The Quarkus adapter keeps a Jackson 2 twin of this codec over the same engine
 * dispatcher.
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
    static final String DEFAULT_PROTOCOL_VERSION = McpProtocol.DEFAULT_PROTOCOL_VERSION;

    static final String SERVER_NAME = McpProtocol.SERVER_NAME;

    private static final String FRAMEWORK = "Spring Boot";
    private static final String INSTRUCTIONS = McpGuidance.instructions(FRAMEWORK);

    private static final Logger log = LoggerFactory.getLogger(BootUiMcpService.class);

    private final McpDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public BootUiMcpService(
            BootUiMcpTools tools, BootUiProperties properties, ObjectMapper objectMapper, String serverVersion) {
        this(tools.tools(), properties, objectMapper, serverVersion);
    }

    public BootUiMcpService(
            ReactiveBootUiMcpTools tools,
            BootUiProperties properties,
            ObjectMapper objectMapper,
            String serverVersion) {
        this(tools.tools(), properties, objectMapper, serverVersion);
    }

    private BootUiMcpService(
            List<McpTool> tools, BootUiProperties properties, ObjectMapper objectMapper, String serverVersion) {
        this.objectMapper = objectMapper;
        int maxResults = Math.max(1, properties.getMcp().getMaxResults());
        int maxConcurrentCalls = Math.max(1, properties.getMcp().getMaxConcurrentCalls());
        this.dispatcher = new McpDispatcher(
                tools,
                McpGuidance.prompts(FRAMEWORK),
                new SpringMcpPanelPolicy(properties),
                serverVersion,
                INSTRUCTIONS,
                maxResults,
                maxConcurrentCalls);
    }

    /** Parse raw request bytes into a Jackson node. */
    public JsonNode readTree(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid JSON-RPC request", ex);
        }
    }

    /**
     * Handles a single JSON-RPC request or notification.
     *
     * @return the JSON-RPC response, or {@code null} for notifications (which have no response)
     */
    public JsonNode handle(JsonNode request) {
        if (request == null || !request.isObject()) {
            return error(null, McpProtocol.INVALID_REQUEST, McpProtocol.MALFORMED_REQUEST_MESSAGE);
        }
        JsonNode id = request.get("id");
        JsonNode jsonrpc = request.get("jsonrpc");
        if (jsonrpc == null || !McpProtocol.JSONRPC_VERSION.equals(jsonrpc.asString())) {
            return error(id, McpProtocol.INVALID_REQUEST, "Request must include jsonrpc: \"2.0\"");
        }
        McpDispatchOutcome outcome = dispatcher.dispatch(parse(request));
        return render(outcome, id);
    }

    private static McpRequest parse(JsonNode request) {
        String jsonrpc = request.path("jsonrpc").asString();
        String method = request.path("method").asString();
        JsonNode id = request.get("id");
        boolean notification = id == null || id.isNull();
        JsonNode params = request.path("params");
        String requestedProtocolVersion = params.path("protocolVersion").asString();
        String toolName = params.path("name").asString();
        JsonNode arguments = params.get("arguments");
        return new McpRequest(
                jsonrpc,
                method,
                notification,
                requestedProtocolVersion,
                toolName,
                rawQuery(arguments),
                rawLimit(arguments),
                rawId(arguments));
    }

    private static String rawQuery(JsonNode arguments) {
        if (arguments == null
                || !arguments.has("query")
                || arguments.get("query").isNull()) {
            return null;
        }
        return arguments.get("query").asString();
    }

    private static String rawId(JsonNode arguments) {
        if (arguments == null || !arguments.has("id") || arguments.get("id").isNull()) {
            return null;
        }
        return arguments.get("id").asString();
    }

    private static Integer rawLimit(JsonNode arguments) {
        if (arguments != null
                && arguments.has("limit")
                && arguments.get("limit").isIntegralNumber()) {
            return arguments.get("limit").asInt();
        }
        return null;
    }

    private JsonNode render(McpDispatchOutcome outcome, JsonNode id) {
        // McpDispatchOutcome is sealed; instanceof patterns (not a switch type pattern) keep this on
        // the project's Java 17 release level.
        if (outcome instanceof NoResponse) {
            return null;
        }
        if (outcome instanceof ProtocolError e) {
            return error(id, e.code(), e.message());
        }
        if (outcome instanceof InitializeResult r) {
            return result(id, renderInitialize(r));
        }
        if (outcome instanceof PingResult) {
            return result(id, JsonNodeFactory.instance.objectNode());
        }
        if (outcome instanceof ToolsListResult r) {
            return result(id, renderToolsList(r));
        }
        if (outcome instanceof PromptsListResult r) {
            return result(id, renderPromptsList(r));
        }
        if (outcome instanceof PromptGetResult r) {
            return result(id, renderPrompt(r.prompt()));
        }
        if (outcome instanceof ToolCallError e) {
            return result(id, toolError(e.message()));
        }
        if (outcome instanceof ToolCallResult r) {
            return renderToolCall(id, r);
        }
        throw new IllegalStateException("Unknown MCP outcome: " + outcome);
    }

    private static ObjectNode renderInitialize(InitializeResult init) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("protocolVersion", init.protocolVersion());

        ObjectNode capabilities = JsonNodeFactory.instance.objectNode();
        ObjectNode toolsCapability = JsonNodeFactory.instance.objectNode();
        toolsCapability.put("listChanged", false);
        capabilities.set("tools", toolsCapability);
        ObjectNode promptsCapability = JsonNodeFactory.instance.objectNode();
        promptsCapability.put("listChanged", false);
        capabilities.set("prompts", promptsCapability);
        response.set("capabilities", capabilities);

        ObjectNode serverInfo = JsonNodeFactory.instance.objectNode();
        serverInfo.put("name", init.serverName());
        serverInfo.put("version", init.serverVersion());
        response.set("serverInfo", serverInfo);

        response.put("instructions", init.instructions());
        return response;
    }

    private static ObjectNode renderToolsList(ToolsListResult list) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (McpToolDescriptor tool : list.tools()) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("inputSchema", schema(tool.schema()));
            ObjectNode outputSchema = JsonNodeFactory.instance.objectNode();
            outputSchema.put("type", tool.outputSchemaType());
            outputSchema.put("description", tool.outputSchemaDescription());
            node.set("outputSchema", outputSchema);
            array.add(node);
        }
        result.set("tools", array);
        return result;
    }

    private static ObjectNode renderPromptsList(PromptsListResult list) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (McpPrompt prompt : list.prompts()) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", prompt.name());
            node.put("description", prompt.description());
            node.set("arguments", JsonNodeFactory.instance.arrayNode());
            array.add(node);
        }
        result.set("prompts", array);
        return result;
    }

    private static ObjectNode renderPrompt(McpPrompt prompt) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("description", prompt.description());
        ArrayNode messages = JsonNodeFactory.instance.arrayNode();
        ObjectNode message = JsonNodeFactory.instance.objectNode();
        message.put("role", "user");
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.put("type", "text");
        content.put("text", prompt.text());
        message.set("content", content);
        messages.add(message);
        result.set("messages", messages);
        return result;
    }

    private JsonNode renderToolCall(JsonNode id, ToolCallResult call) {
        JsonNode payloadNode;
        String text;
        try {
            payloadNode = objectMapper.valueToTree(call.payload());
            text = objectMapper.writeValueAsString(payloadNode);
        } catch (RuntimeException ex) {
            log.debug("BootUI MCP tool result serialization failed", ex);
            return error(id, McpProtocol.INTERNAL_ERROR, ex.getMessage());
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode content = JsonNodeFactory.instance.arrayNode();
        ObjectNode textContent = JsonNodeFactory.instance.objectNode();
        textContent.put("type", "text");
        textContent.put("text", text);
        content.add(textContent);
        result.set("content", content);
        result.set("structuredContent", payloadNode);
        result.put("isError", false);
        return result(id, result);
    }

    private static ObjectNode toolError(String message) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode content = JsonNodeFactory.instance.arrayNode();
        ObjectNode textContent = JsonNodeFactory.instance.objectNode();
        textContent.put("type", "text");
        textContent.put("text", message == null ? McpProtocol.TOOL_CALL_FAILED_MESSAGE : message);
        content.add(textContent);
        result.set("content", content);
        result.put("isError", true);
        return result;
    }

    private static ObjectNode result(JsonNode id, JsonNode payload) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        response.set("id", normalizeId(id));
        response.set("result", payload);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
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

    private static ObjectNode schema(McpToolSchema schema) {
        return switch (schema) {
            case NONE -> emptyObjectSchema();
            case LIMIT -> limitSchema();
            case QUERY_LIMIT -> querySchema();
            case ID -> idSchema();
        };
    }

    private static ObjectNode emptyObjectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.set("properties", JsonNodeFactory.instance.objectNode());
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode limitSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        properties.set("limit", limitProperty());
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode querySchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        ObjectNode query = JsonNodeFactory.instance.objectNode();
        query.put("type", "string");
        query.put("description", "Optional case-insensitive filter applied to the results.");
        properties.set("query", query);
        properties.set("limit", limitProperty());
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode limitProperty() {
        ObjectNode limit = JsonNodeFactory.instance.objectNode();
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put(
                "description",
                "Optional maximum number of items to return. Capped by the bootui.mcp.max-results server limit.");
        return limit;
    }

    private static ObjectNode idSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        ObjectNode id = JsonNodeFactory.instance.objectNode();
        id.put("type", "string");
        id.put("description", "Exact identifier of the resource to fetch.");
        properties.set("id", id);
        schema.set("properties", properties);
        ArrayNode required = JsonNodeFactory.instance.arrayNode();
        required.add("id");
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
