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
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptGetResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptsListResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolsListResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpPrompt;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequest;
import io.github.jdubois.bootui.engine.mcp.McpToolAnnotations;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptor;
import io.github.jdubois.bootui.engine.mcp.McpToolInputSchema;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus (Jackson 2) JSON-RPC envelope codec for the BootUI MCP server — the byte-for-byte twin of
 * the Spring adapter's {@code BootUiMcpService}, over the same framework- and JSON-free engine
 * {@link McpDispatcher}.
 */
@Singleton
public class QuarkusMcpEnvelope {

    private final McpDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final QuarkusMcpFailureReporter failureReporter;
    private final int maxResponseBytes;

    @Inject
    public QuarkusMcpEnvelope(
            McpDispatcher dispatcher,
            ObjectMapper objectMapper,
            QuarkusMcpFailureReporter failureReporter,
            Config config) {
        this(dispatcher, objectMapper, failureReporter, BootUiMcpProducer.maxResponseBytes(config));
    }

    QuarkusMcpEnvelope(McpDispatcher dispatcher, ObjectMapper objectMapper, QuarkusMcpFailureReporter failureReporter) {
        this(dispatcher, objectMapper, failureReporter, McpProtocol.DEFAULT_MAX_RESPONSE_BYTES);
    }

    QuarkusMcpEnvelope(
            McpDispatcher dispatcher,
            ObjectMapper objectMapper,
            QuarkusMcpFailureReporter failureReporter,
            int maxResponseBytes) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.failureReporter = failureReporter;
        this.maxResponseBytes = Math.max(1, maxResponseBytes);
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
        if (jsonrpc == null || !McpProtocol.JSONRPC_VERSION.equals(jsonrpc.asText())) {
            return error(id, McpProtocol.INVALID_REQUEST, "Request must include jsonrpc: \"2.0\"");
        }
        if (id != null && !id.isNull() && !id.isTextual() && !id.isNumber()) {
            return error(null, McpProtocol.INVALID_REQUEST, McpProtocol.INVALID_ID_MESSAGE);
        }
        JsonNode params = request.get("params");
        if (params != null && !params.isObject()) {
            return error(id, McpProtocol.INVALID_PARAMS, McpProtocol.PARAMS_OBJECT_MESSAGE);
        }
        try {
            McpDispatchOutcome outcome = dispatcher.dispatch(parse(request));
            JsonNode response = render(outcome, id);
            if (response != null && objectMapper.writeValueAsBytes(response).length > maxResponseBytes) {
                dispatcher.runtimeStats().recordResponseLimitRefusal();
                return error(id, McpProtocol.RESPONSE_TOO_LARGE, McpProtocol.RESPONSE_TOO_LARGE_MESSAGE);
            }
            return response;
        } catch (JsonProcessingException | RuntimeException | Error failure) {
            failureReporter.report("rendering a response", failure);
            return error(id, McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE);
        }
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
        String jsonrpc = request.path("jsonrpc").asText();
        String method = request.path("method").asText();
        JsonNode id = request.get("id");
        boolean notification = id == null || id.isNull();
        JsonNode params = request.path("params");
        String requestedProtocolVersion = params.path("protocolVersion").asText();
        String toolName = params.path("name").asText();
        JsonNode arguments = params.get("arguments");
        ParsedArguments parsedArguments = parseArguments(arguments);
        return new McpRequest(
                jsonrpc,
                method,
                notification,
                requestedProtocolVersion,
                toolName,
                parsedArguments.query(),
                parsedArguments.limit(),
                parsedArguments.id(),
                parsedArguments.names(),
                parsedArguments.error(),
                parsedArguments.scanId(),
                parsedArguments.offset());
    }

    private static ParsedArguments parseArguments(JsonNode arguments) {
        if (arguments == null) {
            return ParsedArguments.empty();
        }
        if (!arguments.isObject()) {
            return ParsedArguments.error(McpProtocol.ARGUMENTS_OBJECT_MESSAGE);
        }
        Set<String> names = new TreeSet<>();
        arguments.fieldNames().forEachRemaining(names::add);
        JsonNode query = arguments.get("query");
        if (query != null && !query.isTextual()) {
            return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("query", "a string"));
        }
        JsonNode id = arguments.get("id");
        if (id != null && !id.isTextual()) {
            return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("id", "a string"));
        }
        JsonNode limit = arguments.get("limit");
        if (limit != null && (!limit.isIntegralNumber() || !limit.canConvertToInt())) {
            return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("limit", "an integer"));
        }
        if (limit != null && limit.asInt() < 1) {
            return ParsedArguments.error(McpProtocol.invalidArgumentMinimumMessage("limit", 1));
        }
        JsonNode scanId = arguments.get("scanId");
        if (scanId != null && !scanId.isTextual()) {
            return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("scanId", "a string"));
        }
        JsonNode offset = arguments.get("offset");
        if (offset != null && (!offset.isIntegralNumber() || !offset.canConvertToInt())) {
            return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("offset", "an integer"));
        }
        if (offset != null && offset.asInt() < 0) {
            return ParsedArguments.error(McpProtocol.invalidArgumentMinimumMessage("offset", 0));
        }
        return new ParsedArguments(
                query == null ? null : query.asText(),
                limit == null ? null : limit.asInt(),
                id == null ? null : id.asText(),
                names,
                null,
                scanId == null ? null : scanId.asText(),
                offset == null ? null : offset.asInt());
    }

    private record ParsedArguments(
            String query, Integer limit, String id, Set<String> names, String error, String scanId, Integer offset) {
        private static ParsedArguments empty() {
            return new ParsedArguments(null, null, null, Set.of(), null, null, null);
        }

        private static ParsedArguments error(String error) {
            return new ParsedArguments(null, null, null, Set.of(), error, null, null);
        }
    }

    private JsonNode render(McpDispatchOutcome outcome, JsonNode id) {
        if (outcome instanceof NoResponse) {
            return null;
        }
        if (outcome instanceof ProtocolError e) {
            return error(id, e.code(), e.message(), e.data());
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
            node.set("inputSchema", inputSchema(tool.inputSchema()));
            node.set("annotations", annotations(tool.annotations()));
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
        } catch (JsonProcessingException | RuntimeException | Error failure) {
            failureReporter.report("serializing a tool result", failure);
            return error(id, McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE);
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
        return error(id, code, message, null);
    }

    private static ObjectNode error(JsonNode id, int code, String message, Map<String, String> data) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        response.set("id", normalizeId(id));
        ObjectNode err = JsonNodeFactory.instance.objectNode();
        err.put("code", code);
        err.put("message", message == null ? "Error" : message);
        if (data != null && !data.isEmpty()) {
            ObjectNode members = JsonNodeFactory.instance.objectNode();
            data.forEach(members::put);
            err.set("data", members);
        }
        response.set("error", err);
        return response;
    }

    private static JsonNode normalizeId(JsonNode id) {
        return id == null ? JsonNodeFactory.instance.nullNode() : id;
    }

    private static ObjectNode inputSchema(McpToolInputSchema input) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        for (McpToolInputSchema.Property property : input.properties()) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("type", property.type());
            if (property.minimum() != null) {
                node.put("minimum", property.minimum());
            }
            if (property.minLength() != null) {
                node.put("minLength", property.minLength());
            }
            if (property.defaultValue() != null) {
                node.put("default", property.defaultValue());
            }
            node.put("description", property.description());
            if (!property.examples().isEmpty()) {
                ArrayNode examples = JsonNodeFactory.instance.arrayNode();
                property.examples().forEach(examples::add);
                node.set("examples", examples);
            }
            properties.set(property.name(), node);
        }
        schema.set("properties", properties);
        if (!input.required().isEmpty()) {
            ArrayNode required = JsonNodeFactory.instance.arrayNode();
            input.required().forEach(required::add);
            schema.set("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode annotations(McpToolAnnotations hints) {
        ObjectNode annotations = JsonNodeFactory.instance.objectNode();
        annotations.put("readOnlyHint", hints.readOnlyHint());
        annotations.put("destructiveHint", hints.destructiveHint());
        annotations.put("idempotentHint", hints.idempotentHint());
        annotations.put("openWorldHint", hints.openWorldHint());
        return annotations;
    }
}
