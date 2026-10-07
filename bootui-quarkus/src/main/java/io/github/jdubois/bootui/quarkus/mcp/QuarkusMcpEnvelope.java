package io.github.jdubois.bootui.quarkus.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpCallStart;
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
import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Serve;
import io.github.jdubois.bootui.engine.mcp.McpExchange;
import io.github.jdubois.bootui.engine.mcp.McpProgressToken;
import io.github.jdubois.bootui.engine.mcp.McpPrompt;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequest;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpRequestMeta;
import io.github.jdubois.bootui.engine.mcp.McpRequestMeta.Field;
import io.github.jdubois.bootui.engine.mcp.McpStreamingCall;
import io.github.jdubois.bootui.engine.mcp.McpToolAnnotations;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptor;
import io.github.jdubois.bootui.engine.mcp.McpToolInputSchema;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus (Jackson 2) JSON-RPC envelope codec for the BootUI MCP server — the byte-for-byte twin of
 * the Spring adapter's {@code BootUiMcpService}, over the same framework- and JSON-free engine
 * {@link McpDispatcher}, including its dual-era request flow ({@link McpExchange}) and modern MCP 2026-07-28 result
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
    public record Reply(int status, JsonNode body, Stream stream) {

        public Reply(int status, JsonNode body) {
            this(status, body, null);
        }
    }

    /**
     * A {@code tools/call} to answer on a request-scoped {@code text/event-stream}: the transport opens the stream,
     * renders each event with {@link #renderProgress} and {@link #renderFinal}, and {@linkplain McpStreamingCall#start
     * starts} the call, or {@linkplain McpStreamingCall#cancel cancels} it if the stream cannot be opened.
     *
     * @param call the call, holding a concurrency permit until it ends
     * @param id the request id the final response echoes
     */
    public record Stream(McpStreamingCall call, JsonNode id) {}

    /**
     * Answers one parsed MCP {@code POST} body through the engine's request flow ({@link McpExchange}): this codec only
     * extracts the envelope fields and renders the plan, so every stack answers byte-identically.
     */
    public Reply exchange(JsonNode request, McpRequestHeaders headers, boolean enabled) {
        return exchange(request, headers, enabled, false);
    }

    /**
     * Like {@link #exchange(JsonNode, McpRequestHeaders, boolean)}, but a modern progress call from a client whose
     * {@code Accept} lists {@code text/event-stream} may answer with a {@link Stream}.
     */
    public Reply exchange(JsonNode request, McpRequestHeaders headers, boolean enabled, boolean acceptsEventStream) {
        return answer(request, McpExchange.plan(envelope(request), headers, enabled), acceptsEventStream);
    }

    private Reply answer(JsonNode request, McpExchange.Plan plan, boolean acceptsEventStream) {
        if (plan instanceof McpExchange.Plan.Reject reject) {
            return new Reply(reject.httpStatus(), rejection(request, reject));
        }
        if (plan instanceof McpExchange.Plan.Accept) {
            return new Reply(202, null);
        }
        if (plan instanceof McpExchange.Plan.Disabled disabled) {
            JsonNode id = request != null && request.isObject() ? request.get("id") : null;
            return new Reply(200, error(id, disabled.code(), McpProtocol.SERVER_DISABLED_MESSAGE));
        }
        Reply reply = respond(request, ((McpExchange.Plan.Dispatch) plan).serve(), acceptsEventStream);
        return reply.body() == null && reply.stream() == null ? new Reply(202, null) : reply;
    }

    /** The neutral envelope fields of {@code request}; the decisions are {@link McpExchange}'s. */
    private static McpExchange.Envelope envelope(JsonNode request) {
        if (request == null || !request.isObject()) {
            return McpExchange.Envelope.notAnObject(request != null && request.isArray());
        }
        JsonNode id = request.get("id");
        JsonNode jsonrpc = request.get("jsonrpc");
        JsonNode params = request.get("params");
        JsonNode method = request.get("method");
        JsonNode name = request.path("params").get("name");
        return new McpExchange.Envelope(
                false,
                true,
                jsonrpc != null && McpProtocol.JSONRPC_VERSION.equals(text(jsonrpc)),
                id == null || id.isNull()
                        ? McpExchange.IdShape.ABSENT_OR_NULL
                        : id.isTextual() || id.isNumber()
                                ? McpExchange.IdShape.STRING_OR_NUMBER
                                : McpExchange.IdShape.INVALID,
                params == null || params.isObject(),
                method != null && method.isTextual() ? method.asText() : null,
                name != null && name.isTextual() ? name.asText() : null,
                meta(request));
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

    /**
     * The text of an envelope field ({@code jsonrpc}, {@code method}, {@code params.name}, {@code
     * params.protocolVersion}): the string when it is one, otherwise empty. Jackson 2 and Jackson 3 coerce {@code
     * null}, numbers, and containers differently, so neither coercion is used and every stack sees the same request.
     */
    private static String text(JsonNode node) {
        return node.isTextual() ? node.asText() : "";
    }

    private static ObjectNode rejection(JsonNode request, McpExchange.Plan.Reject reject) {
        JsonNode id = null;
        if (request != null && request.isObject() && reject.idEcho() != McpExchange.IdEcho.NULL) {
            JsonNode candidate = request.get("id");
            if (reject.idEcho() == McpExchange.IdEcho.AS_SENT
                    || (candidate != null && (candidate.isTextual() || candidate.isNumber()))) {
                id = candidate;
            }
        }
        ObjectNode response = error(id, reject.code(), reject.message());
        if (reject.hasVersionData()) {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            ArrayNode supported = data.putArray("supported");
            reject.supportedVersions().forEach(supported::add);
            data.put("requested", reject.requestedVersion());
            ((ObjectNode) response.get("error")).set("data", data);
        }
        return response;
    }
    /** One {@code notifications/progress} of a stream, as compact JSON. */
    public String renderProgress(McpProgressToken token, ProgressEvent event) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        if (token.isText()) {
            params.put("progressToken", token.text());
        } else {
            params.put("progressToken", token.number());
        }
        putNumber(params, "progress", event.progress());
        if (event.total() != null) {
            putNumber(params, "total", event.total());
        }
        params.put("message", event.message());
        ObjectNode notification = JsonNodeFactory.instance.objectNode();
        notification.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        notification.put("method", McpProtocol.PROGRESS_NOTIFICATION);
        notification.set("params", params);
        return notification.toString();
    }

    /** Integral values render as integers so every stack writes the same bytes. */
    private static void putNumber(ObjectNode node, String field, double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            node.put(field, (long) value);
        } else {
            node.put(field, value);
        }
    }

    /**
     * The final JSON-RPC response of a stream in {@code era}, as compact JSON: rendered and size-limited exactly like a
     * JSON response, so a stream never carries more than {@code bootui.mcp.max-response-bytes}.
     */
    public String renderFinal(JsonNode id, McpEra era, McpDispatchOutcome outcome) {
        try {
            JsonNode response = render(outcome, id, era);
            byte[] bytes = objectMapper.writeValueAsBytes(response);
            if (bytes.length > maxResponseBytes) {
                dispatcher.runtimeStats().recordResponseLimitRefusal();
                return error(id, era, McpProtocol.RESPONSE_TOO_LARGE, McpProtocol.RESPONSE_TOO_LARGE_MESSAGE)
                        .toString();
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (JsonProcessingException | RuntimeException | Error failure) {
            failureReporter.report("rendering a response", failure);
            return error(id, era, McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE)
                    .toString();
        }
    }

    /** {@link #renderFinal(JsonNode, McpEra, McpDispatchOutcome)} for a modern stream. */
    public String renderFinal(JsonNode id, McpDispatchOutcome outcome) {
        return renderFinal(id, McpEra.MODERN, outcome);
    }

    /** Parse raw request bytes into a Jackson node. */
    public JsonNode readTree(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid JSON-RPC request", ex);
        }
    }

    public JsonNode handle(JsonNode request) {
        McpExchange.Envelope envelope = envelope(request);
        McpExchange.Plan.Reject invalid = McpExchange.checkEnvelope(envelope, McpEra.LEGACY);
        return answer(
                        request,
                        invalid != null ? invalid : new McpExchange.Plan.Dispatch(new Serve(McpEra.LEGACY, null, null)),
                        false)
                .body();
    }
    /** The JSON-RPC response with the HTTP status its outcome implies; a {@code null} body for a notification. */
    private Reply respond(JsonNode request, Serve serve, boolean acceptsEventStream) {
        McpEra era = serve.era();
        JsonNode id = request.get("id");
        try {
            McpCallStart start = dispatcher.start(parse(request, serve), acceptsEventStream);
            if (start instanceof McpCallStart.Stream stream) {
                return new Reply(200, null, new Stream(stream.call(), id));
            }
            McpDispatchOutcome outcome = ((McpCallStart.Immediate) start).outcome();
            int status = McpProtocol.httpStatus(era, outcome);
            JsonNode response = render(outcome, id, era);
            if (response != null && objectMapper.writeValueAsBytes(response).length > maxResponseBytes) {
                dispatcher.runtimeStats().recordResponseLimitRefusal();
                return new Reply(
                        200, error(id, era, McpProtocol.RESPONSE_TOO_LARGE, McpProtocol.RESPONSE_TOO_LARGE_MESSAGE));
            }
            return new Reply(status, response);
        } catch (JsonProcessingException | RuntimeException | Error failure) {
            failureReporter.report("rendering a response", failure);
            return new Reply(200, error(id, era, McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE));
        }
    }

    private static McpRequest parse(JsonNode request, Serve serve) {
        String jsonrpc = text(request.path("jsonrpc"));
        String method = text(request.path("method"));
        JsonNode id = request.get("id");
        boolean notification = id == null || id.isNull();
        JsonNode params = request.path("params");
        String requestedProtocolVersion = text(params.path("protocolVersion"));
        String toolName = text(params.path("name"));
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
            return error(id, McpProtocol.wireErrorCode(era, e.code()), e.message(), e.data());
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
        if (outcome instanceof McpDispatchOutcome.Cancelled) {
            return error(id, era, McpProtocol.REQUEST_CANCELLED, McpProtocol.REQUEST_CANCELLED_MESSAGE);
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
            node.set("inputSchema", inputSchema(tool.inputSchema()));
            node.set("annotations", annotations(tool.annotations()));
            ObjectNode outputSchema = JsonNodeFactory.instance.objectNode();
            outputSchema.put("type", tool.outputSchemaType());
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
