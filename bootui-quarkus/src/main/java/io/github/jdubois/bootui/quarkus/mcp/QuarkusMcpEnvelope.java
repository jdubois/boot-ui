package io.github.jdubois.bootui.quarkus.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.InitializeResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.NoResponse;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PingResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolsListResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequest;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptor;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import jakarta.inject.Singleton;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Quarkus (Jackson 2) JSON-RPC envelope codec for the BootUI MCP server — the byte-for-byte twin of
 * the Spring adapter's {@code BootUiMcpService}, over the same framework- and JSON-free engine
 * {@link McpDispatcher}.
 *
 * <p>It does only the irreducibly-Jackson part: parse a request node into a neutral
 * {@link McpRequest}, dispatch it, and render the {@link McpDispatchOutcome} back to JSON (echoing the
 * id, building each tool's input schema, and serializing the tool payload). All protocol decisions
 * (method routing, per-panel gating, tool lookup, argument capping, error codes and canonical
 * messages) live in the engine, so the two adapters answer identically.
 *
 * <p>Jackson 2 differences from the Jackson 3 twin: node text is read with {@code asText()} (not
 * {@code asString()}), and {@link ObjectMapper#writeValueAsString} throws the <em>checked</em>
 * {@link JsonProcessingException}, so the tool-result serialization catches it explicitly (a failure
 * is reported in-band as a {@code -32603} error, exactly like the Spring twin).
 */
@Singleton
public class QuarkusMcpEnvelope {

    private static final Logger LOG = Logger.getLogger(QuarkusMcpEnvelope.class.getName());

    private final McpDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public QuarkusMcpEnvelope(McpDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles a single JSON-RPC request or notification.
     *
     * @return the JSON-RPC response, or {@code null} for notifications (which have no response)
     */
    public JsonNode handle(JsonNode request) {
        if (request == null || !request.isObject()) {
            return error(null, McpProtocol.INVALID_PARAMS, McpProtocol.MALFORMED_REQUEST_MESSAGE);
        }
        JsonNode id = request.get("id");
        McpDispatchOutcome outcome = dispatcher.dispatch(parse(request));
        return render(outcome, id);
    }

    /**
     * Builds the JSON-RPC error returned (HTTP 200, error {@link McpProtocol#SERVER_DISABLED}) while
     * the server is disabled, preserving the request id when present so a compliant client can
     * correlate it.
     */
    public JsonNode disabledError(JsonNode request) {
        JsonNode id = request != null && request.isObject() ? request.get("id") : null;
        return error(id, McpProtocol.SERVER_DISABLED, McpProtocol.SERVER_DISABLED_MESSAGE);
    }

    private static McpRequest parse(JsonNode request) {
        String method = request.path("method").asText();
        JsonNode id = request.get("id");
        boolean notification = id == null || id.isNull();
        JsonNode params = request.path("params");
        String requestedProtocolVersion = params.path("protocolVersion").asText();
        String toolName = params.path("name").asText();
        JsonNode arguments = params.get("arguments");
        return new McpRequest(
                method, notification, requestedProtocolVersion, toolName, rawQuery(arguments), rawLimit(arguments));
    }

    private static String rawQuery(JsonNode arguments) {
        if (arguments == null
                || !arguments.has("query")
                || arguments.get("query").isNull()) {
            return null;
        }
        return arguments.get("query").asText();
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
            array.add(node);
        }
        result.set("tools", array);
        return result;
    }

    private JsonNode renderToolCall(JsonNode id, ToolCallResult call) {
        String text;
        try {
            text = objectMapper.writeValueAsString(call.payload());
        } catch (JsonProcessingException | RuntimeException ex) {
            LOG.log(Level.FINE, "BootUI MCP tool result serialization failed", ex);
            return error(id, McpProtocol.INTERNAL_ERROR, ex.getMessage());
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode content = JsonNodeFactory.instance.arrayNode();
        ObjectNode textContent = JsonNodeFactory.instance.objectNode();
        textContent.put("type", "text");
        textContent.put("text", text);
        content.add(textContent);
        result.set("content", content);
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

    private static ObjectNode schema(McpToolSchema schema) {
        return switch (schema) {
            case NONE -> emptyObjectSchema();
            case LIMIT -> limitSchema();
            case QUERY_LIMIT -> querySchema();
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
}
