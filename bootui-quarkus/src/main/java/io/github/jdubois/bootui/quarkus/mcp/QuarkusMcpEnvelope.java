package io.github.jdubois.bootui.quarkus.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.DiscoverResult;
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
import io.github.jdubois.bootui.engine.mcp.McpEra;
import io.github.jdubois.bootui.engine.mcp.McpEraDecision;
import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Rejected;
import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Serve;
import io.github.jdubois.bootui.engine.mcp.McpEraResolver;
import io.github.jdubois.bootui.engine.mcp.McpProgressToken;
import io.github.jdubois.bootui.engine.mcp.McpPrompt;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequest;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpRequestMeta;
import io.github.jdubois.bootui.engine.mcp.McpRequestMeta.Field;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptor;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus (Jackson 2) JSON-RPC envelope codec for the BootUI MCP server — the byte-for-byte twin of
 * the Spring adapter's {@code BootUiMcpService}, over the same framework- and JSON-free engine
 * {@link McpDispatcher}, including its dual-era selection ({@link McpEraResolver}) and modern MCP 2026-07-28 result
 * decoration.
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

    /**
     * The HTTP outcome of one MCP {@code POST}.
     *
     * @param status the HTTP status
     * @param body the JSON-RPC response, or {@code null} for {@code 202 Accepted} with no body
     */
    public record Reply(int status, JsonNode body) {}

    /**
     * Answers one parsed MCP {@code POST} body: refuses a batch, selects the protocol era and validates its metadata,
     * short-circuits while the server is disabled, then dispatches. The Spring adapter's {@code BootUiMcpService}
     * answers byte-identically.
     */
    public Reply exchange(JsonNode request, McpRequestHeaders headers, boolean enabled) {
        if (request != null && request.isArray()) {
            return new Reply(400, error(null, McpProtocol.INVALID_REQUEST, McpProtocol.BATCH_NOT_SUPPORTED_MESSAGE));
        }
        McpEraDecision decision = resolveEra(request, headers);
        if (decision instanceof Rejected rejected) {
            return new Reply(rejected.httpStatus(), rejection(request, rejected));
        }
        Serve serve = (Serve) decision;
        if (!enabled) {
            if (isNotification(request)) {
                return new Reply(202, null);
            }
            JsonNode id = request != null && request.isObject() ? request.get("id") : null;
            return new Reply(
                    200, error(id, serve.era(), McpProtocol.SERVER_DISABLED, McpProtocol.SERVER_DISABLED_MESSAGE));
        }
        JsonNode response = handle(request, serve);
        if (response == null) {
            return new Reply(202, null);
        }
        JsonNode code = response.path("error").path("code");
        int status = code.isIntegralNumber() ? McpProtocol.errorHttpStatus(serve.era(), code.asInt()) : 200;
        return new Reply(status, response);
    }

    private static McpEraDecision resolveEra(JsonNode request, McpRequestHeaders headers) {
        if (request == null || !request.isObject()) {
            return McpEraResolver.resolve(null, false, null, McpRequestMeta.NONE, headers);
        }
        JsonNode method = request.get("method");
        JsonNode name = request.path("params").get("name");
        return McpEraResolver.resolve(
                method != null && method.isTextual() ? method.asText() : null,
                isNotification(request),
                name != null && name.isTextual() ? name.asText() : null,
                meta(request),
                headers);
    }

    private static McpRequestMeta meta(JsonNode request) {
        JsonNode meta = request.path("params").path("_meta");
        if (!meta.isObject()) {
            return McpRequestMeta.NONE;
        }
        JsonNode version = meta.get(McpProtocol.META_PROTOCOL_VERSION);
        JsonNode capabilities = meta.get(McpProtocol.META_CLIENT_CAPABILITIES);
        JsonNode token = meta.get(McpProtocol.META_PROGRESS_TOKEN);
        McpProgressToken progressToken = null;
        Field tokenField = Field.ABSENT;
        if (token != null) {
            if (token.isTextual()) {
                progressToken = McpProgressToken.of(token.asText());
            } else if (token.isIntegralNumber() && token.canConvertToLong()) {
                progressToken = McpProgressToken.of(token.asLong());
            }
            tokenField = progressToken == null ? Field.INVALID : Field.VALID;
        }
        return new McpRequestMeta(
                version == null ? Field.ABSENT : version.isTextual() ? Field.VALID : Field.INVALID,
                version != null && version.isTextual() ? version.asText() : null,
                capabilities == null ? Field.ABSENT : capabilities.isObject() ? Field.VALID : Field.INVALID,
                tokenField,
                progressToken);
    }

    private static boolean isNotification(JsonNode request) {
        return request != null
                && request.isObject()
                && !request.hasNonNull("id")
                && McpProtocol.JSONRPC_VERSION.equals(request.path("jsonrpc").asText())
                && !request.path("method").asText().isBlank();
    }

    /** A modern rejection echoes a readable request id; a legacy one keeps BootUI 1.x's {@code null} id. */
    private static ObjectNode rejection(JsonNode request, Rejected rejected) {
        JsonNode id = null;
        if (rejected.era() == McpEra.MODERN && request != null && request.isObject()) {
            JsonNode candidate = request.get("id");
            if (candidate != null && (candidate.isTextual() || candidate.isNumber())) {
                id = candidate;
            }
        }
        ObjectNode response = error(id, rejected.era(), rejected.code(), rejected.message());
        if (rejected.hasVersionData()) {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            ArrayNode supported = data.putArray("supported");
            rejected.supportedVersions().forEach(supported::add);
            data.put("requested", rejected.requestedVersion());
            ((ObjectNode) response.get("error")).set("data", data);
        }
        return response;
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
        return handle(request, new Serve(McpEra.LEGACY, null, null));
    }

    private JsonNode handle(JsonNode request, Serve serve) {
        McpEra era = serve.era();
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
            McpDispatchOutcome outcome = dispatcher.dispatch(parse(request, serve));
            JsonNode response = render(outcome, id, era);
            if (response != null && objectMapper.writeValueAsBytes(response).length > maxResponseBytes) {
                dispatcher.runtimeStats().recordResponseLimitRefusal();
                return error(id, era, McpProtocol.RESPONSE_TOO_LARGE, McpProtocol.RESPONSE_TOO_LARGE_MESSAGE);
            }
            return response;
        } catch (JsonProcessingException | RuntimeException | Error failure) {
            failureReporter.report("rendering a response", failure);
            return error(id, era, McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE);
        }
    }

    private static McpRequest parse(JsonNode request, Serve serve) {
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
                parsedArguments.offset(),
                serve.era(),
                serve.progressToken());
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

    private JsonNode render(McpDispatchOutcome outcome, JsonNode id, McpEra era) {
        if (outcome instanceof NoResponse) {
            return null;
        }
        if (outcome instanceof ProtocolError e) {
            return error(id, era, e.code(), e.message());
        }
        if (outcome instanceof InitializeResult r) {
            return result(id, era, renderInitialize(r), false);
        }
        if (outcome instanceof DiscoverResult r) {
            return result(id, era, renderDiscover(r), true);
        }
        if (outcome instanceof PingResult) {
            return result(id, era, JsonNodeFactory.instance.objectNode(), false);
        }
        if (outcome instanceof ToolsListResult r) {
            return result(id, era, renderToolsList(r), true);
        }
        if (outcome instanceof PromptsListResult r) {
            return result(id, era, renderPromptsList(r), true);
        }
        if (outcome instanceof PromptGetResult r) {
            return result(id, era, renderPrompt(r.prompt()), false);
        }
        if (outcome instanceof ToolCallError e) {
            return result(id, era, toolError(e.message()), false);
        }
        if (outcome instanceof ToolCallResult r) {
            return renderToolCall(id, era, r);
        }
        throw new IllegalStateException("Unknown MCP outcome: " + outcome);
    }

    private static ObjectNode renderInitialize(InitializeResult init) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("protocolVersion", init.protocolVersion());
        response.set("capabilities", capabilities());

        ObjectNode serverInfo = JsonNodeFactory.instance.objectNode();
        serverInfo.put("name", init.serverName());
        serverInfo.put("version", init.serverVersion());
        response.set("serverInfo", serverInfo);

        response.put("instructions", init.instructions());
        return response;
    }

    private static ObjectNode capabilities() {
        ObjectNode capabilities = JsonNodeFactory.instance.objectNode();
        ObjectNode toolsCapability = JsonNodeFactory.instance.objectNode();
        toolsCapability.put("listChanged", false);
        capabilities.set("tools", toolsCapability);
        ObjectNode promptsCapability = JsonNodeFactory.instance.objectNode();
        promptsCapability.put("listChanged", false);
        capabilities.set("prompts", promptsCapability);
        return capabilities;
    }

    private static ObjectNode renderDiscover(DiscoverResult discover) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        ArrayNode versions = response.putArray("supportedVersions");
        discover.supportedVersions().forEach(versions::add);
        response.set("capabilities", capabilities());
        response.put("instructions", discover.instructions());
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

    private JsonNode renderToolCall(JsonNode id, McpEra era, ToolCallResult call) {
        JsonNode payloadNode;
        String text;
        try {
            payloadNode = objectMapper.valueToTree(call.payload());
            text = objectMapper.writeValueAsString(payloadNode);
        } catch (JsonProcessingException | RuntimeException | Error failure) {
            failureReporter.report("serializing a tool result", failure);
            return error(id, era, McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE);
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
        return result(id, era, result, false);
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

    /**
     * A JSON-RPC result. A modern result also carries {@code resultType}, the server's identity in {@code _meta}, and,
     * for the cacheable discovery and list results, {@code ttlMs} and {@code cacheScope}.
     */
    private ObjectNode result(JsonNode id, McpEra era, ObjectNode payload, boolean cacheable) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        response.set("id", normalizeId(id));
        response.set("result", era == McpEra.MODERN ? modernResult(payload, cacheable) : payload);
        return response;
    }

    private ObjectNode modernResult(ObjectNode payload, boolean cacheable) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("resultType", McpProtocol.RESULT_TYPE_COMPLETE);
        result.setAll(payload);
        ObjectNode serverInfo = JsonNodeFactory.instance.objectNode();
        serverInfo.put("name", McpProtocol.SERVER_NAME);
        serverInfo.put("version", dispatcher.serverVersion());
        result.putObject("_meta").set(McpProtocol.META_SERVER_INFO, serverInfo);
        if (cacheable) {
            result.put("ttlMs", McpProtocol.CACHE_TTL_MILLIS);
            result.put("cacheScope", McpProtocol.CACHE_SCOPE);
        }
        return result;
    }

    private static ObjectNode error(JsonNode id, McpEra era, int code, String message) {
        return error(id, McpProtocol.wireErrorCode(era, code), message);
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
            case OPTIONAL_ID -> optionalIdSchema();
            case RULE_VIOLATIONS -> ruleViolationsSchema();
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

    private static ObjectNode optionalIdSchema() {
        ObjectNode schema = idSchema();
        schema.remove("required");
        ((ObjectNode) schema.get("properties").get("id"))
                .put("description", "Optional run id; omitted or previous selects the newest kept run.");
        return schema;
    }

    private static ObjectNode ruleViolationsSchema() {
        ObjectNode schema = idSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        ((ObjectNode) properties.get("id")).put("minLength", 1);
        ObjectNode scanId = JsonNodeFactory.instance.objectNode();
        scanId.put("type", "string");
        scanId.put("minLength", 1);
        scanId.put("description", "The cached report's violationDetails.scanId. Never starts a scan.");
        properties.set("scanId", scanId);
        ((ArrayNode) schema.get("required")).add("scanId");
        ObjectNode offset = JsonNodeFactory.instance.objectNode();
        offset.put("type", "integer");
        offset.put("minimum", 0);
        offset.put("default", 0);
        properties.set("offset", offset);
        ObjectNode limit = limitProperty();
        limit.put("default", 100);
        limit.put("description", "Page size, default 100, capped at min(1000, bootui.mcp.max-results).");
        properties.set("limit", limit);
        return schema;
    }
}
