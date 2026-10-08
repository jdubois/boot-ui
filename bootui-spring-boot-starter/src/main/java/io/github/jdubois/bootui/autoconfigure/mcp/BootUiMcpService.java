package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiMcpTools;
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
import io.github.jdubois.bootui.engine.mcp.McpFailureReporter;
import io.github.jdubois.bootui.engine.mcp.McpGuidance;
import io.github.jdubois.bootui.engine.mcp.McpProgressToken;
import io.github.jdubois.bootui.engine.mcp.McpPrompt;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequest;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpRequestKey;
import io.github.jdubois.bootui.engine.mcp.McpRequestMeta;
import io.github.jdubois.bootui.engine.mcp.McpRequestMeta.Field;
import io.github.jdubois.bootui.engine.mcp.McpStreamingCall;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolAnnotations;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptor;
import io.github.jdubois.bootui.engine.mcp.McpToolInputSchema;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spring Boot (Jackson 3) JSON-RPC envelope codec for the BootUI MCP server.
 *
 * <p>This is a transport-agnostic JSON-RPC 2.0 handler. {@link BootUiMcpController} adapts it to a
 * single loopback HTTP endpoint; the handler itself only understands JSON-RPC messages. It is dual-era: a legacy
 * MCP 2025-06-18 client uses {@code initialize}, {@code notifications/initialized} (notification), and {@code ping};
 * a modern MCP 2026-07-28 client, recognized by its per-request {@code _meta} (see {@link McpExchange}), uses
 * {@code server/discover} and receives {@code resultType}, server metadata, and cache hints on every result. Both
 * eras share {@code tools/list}, {@code tools/call}, {@code prompts/list}, and {@code prompts/get}.
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
    private final McpFailureReporter failureReporter;
    private final int maxResponseBytes;

    public BootUiMcpService(
            BootUiMcpTools tools, BootUiProperties properties, ObjectMapper objectMapper, String serverVersion) {
        this(tools::tools, tools::panelUnavailableReason, properties, objectMapper, serverVersion);
    }

    public BootUiMcpService(
            ReactiveBootUiMcpTools tools,
            BootUiProperties properties,
            ObjectMapper objectMapper,
            String serverVersion) {
        this(tools::tools, tools::panelUnavailableReason, properties, objectMapper, serverVersion);
    }

    private BootUiMcpService(
            Supplier<List<McpTool>> tools,
            Function<String, String> panelUnavailableReason,
            BootUiProperties properties,
            ObjectMapper objectMapper,
            String serverVersion) {
        this(
                tools,
                panelUnavailableReason,
                properties,
                objectMapper,
                serverVersion,
                (operation, failure) -> log.error("BootUI MCP failure while {}", operation, failure));
    }

    BootUiMcpService(
            List<McpTool> tools,
            BootUiProperties properties,
            ObjectMapper objectMapper,
            String serverVersion,
            McpFailureReporter failureReporter) {
        this(() -> tools, properties, objectMapper, serverVersion, failureReporter);
    }

    BootUiMcpService(
            Supplier<List<McpTool>> tools,
            BootUiProperties properties,
            ObjectMapper objectMapper,
            String serverVersion,
            McpFailureReporter failureReporter) {
        this(tools, panelId -> null, properties, objectMapper, serverVersion, failureReporter);
    }

    BootUiMcpService(
            Supplier<List<McpTool>> tools,
            Function<String, String> panelUnavailableReason,
            BootUiProperties properties,
            ObjectMapper objectMapper,
            String serverVersion,
            McpFailureReporter failureReporter) {
        this.objectMapper = objectMapper;
        this.failureReporter = failureReporter;
        this.maxResponseBytes = Math.max(1, properties.getMcp().getMaxResponseBytes());
        int maxResults = Math.max(1, properties.getMcp().getMaxResults());
        int maxConcurrentCalls = Math.max(1, properties.getMcp().getMaxConcurrentCalls());
        long executionTimeoutMillis =
                Math.max(1, properties.getMcp().getExecutionTimeout().toMillis());
        this.dispatcher = new McpDispatcher(
                tools,
                McpGuidance.prompts(FRAMEWORK),
                new SpringMcpPanelPolicy(properties),
                serverVersion,
                INSTRUCTIONS,
                maxResults,
                maxConcurrentCalls,
                executionTimeoutMillis,
                failureReporter,
                panelUnavailableReason);
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

    /** Every value of the MCP request headers, read case-insensitively from Spring's headers. */
    public static McpRequestHeaders headers(HttpHeaders headers) {
        return new McpRequestHeaders(
                headers.get(McpProtocol.PROTOCOL_VERSION_HEADER),
                headers.get(McpProtocol.METHOD_HEADER),
                headers.get(McpProtocol.NAME_HEADER));
    }

    /**
     * Answers one parsed MCP {@code POST} body through the engine's request flow ({@link McpExchange}): this codec only
     * extracts the envelope fields and renders the plan, so every stack answers byte-identically.
     */
    public Reply exchange(JsonNode request, McpRequestHeaders headers, boolean enabled) {
        return exchange(request, headers, enabled, false);
    }

    /**
     * Like {@link #exchange(JsonNode, McpRequestHeaders, boolean)}, but a progress call, in either era, from a client whose
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
                        : id.isString() || id.isNumber()
                                ? McpExchange.IdShape.STRING_OR_NUMBER
                                : McpExchange.IdShape.INVALID,
                params == null || params.isObject(),
                method != null && method.isString() ? method.asString() : null,
                name != null && name.isString() ? name.asString() : null,
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
            if (token.isString()) {
                progressToken = McpProgressToken.of(token.asString());
            } else if (token.isIntegralNumber() && token.canConvertToLong()) {
                progressToken = McpProgressToken.of(token.asLong());
            }
            tokenField = progressToken == null ? Field.INVALID : Field.VALID;
        }
        return new McpRequestMeta(
                version == null ? Field.ABSENT : version.isString() ? Field.VALID : Field.INVALID,
                version != null && version.isString() ? version.asString() : null,
                capabilities == null ? Field.ABSENT : capabilities.isObject() ? Field.VALID : Field.INVALID,
                tokenField,
                progressToken);
    }

    /**
     * The text of an envelope field ({@code jsonrpc}, {@code method}, {@code params.name}, {@code
     * params.protocolVersion}): the string when it is one, otherwise empty. Jackson 3 and Jackson 2 coerce {@code
     * null}, numbers, and containers differently, so neither coercion is used and every stack sees the same request.
     */
    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : "";
    }

    private static ObjectNode rejection(JsonNode request, McpExchange.Plan.Reject reject) {
        JsonNode id = null;
        if (request != null && request.isObject() && reject.idEcho() != McpExchange.IdEcho.NULL) {
            JsonNode candidate = request.get("id");
            if (reject.idEcho() == McpExchange.IdEcho.AS_SENT
                    || (candidate != null && (candidate.isString() || candidate.isNumber()))) {
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
    /**
     * One {@code notifications/progress} of a stream, as compact JSON, or {@code null} when it would exceed {@code
     * bootui.mcp.max-response-bytes}: the transport then sends nothing for that event.
     */
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
        String compact = notification.toString();
        return McpExchange.progressFits(
                        compact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, maxResponseBytes)
                ? compact
                : null;
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
     * The final JSON-RPC response of a stream in {@code era}, as compact JSON on one line whatever the application's
     * mapper is configured to do (an indenting mapper would break the SSE framing), and size-limited on those bytes
     * exactly like a JSON response, so a stream never carries more than {@code bootui.mcp.max-response-bytes}.
     */
    public String renderFinal(JsonNode id, McpEra era, McpDispatchOutcome outcome) {
        try {
            String compact = render(outcome, id, era).toString();
            McpExchange.Plan.Reject tooLarge = McpExchange.checkResponseSize(
                    era, compact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, maxResponseBytes);
            if (tooLarge != null) {
                dispatcher.runtimeStats().recordResponseLimitRefusal();
                return error(id, tooLarge.code(), tooLarge.message()).toString();
            }
            return compact;
        } catch (RuntimeException | Error failure) {
            failureReporter.report("rendering a response", failure);
            return error(id, era, McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE)
                    .toString();
        }
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
            McpExchange.Plan.Reject tooLarge = response == null
                    ? null
                    : McpExchange.checkResponseSize(
                            era, objectMapper.writeValueAsBytes(response).length, maxResponseBytes);
            if (tooLarge != null) {
                dispatcher.runtimeStats().recordResponseLimitRefusal();
                return new Reply(tooLarge.httpStatus(), error(id, tooLarge.code(), tooLarge.message()));
            }
            return new Reply(status, response);
        } catch (RuntimeException | Error failure) {
            failureReporter.report("rendering a response", failure);
            return new Reply(200, error(id, era, McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE));
        }
    }

    public McpDispatcher dispatcher() {
        return dispatcher;
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
                serve.progressToken(),
                // Only a call that can be cancelled, or a cancellation, needs the key.
                "tools/call".equals(method) ? requestKey(id) : null,
                "notifications/cancelled".equals(method) ? requestKey(params.get("requestId")) : null,
                "notifications/cancelled".equals(method) ? cancelReason(params.get("reason")) : null);
    }

    /**
     * The canonical key of a JSON-RPC id, so a cancellation finds its request by value; {@code null} for an id that has
     * none, which then simply cannot be cancelled: anything but a string, an integer, or a fractional number whose
     * double value is whole, non-zero, and below 2^53.
     */
    static String requestKey(JsonNode id) {
        if (id == null) {
            return null;
        }
        if (id.isString()) {
            return McpRequestKey.text(id.asString());
        }
        if (id.isIntegralNumber()) {
            return McpRequestKey.number(id.decimalValue());
        }
        if (!id.isFloatingPointNumber()) {
            return null;
        }
        if (id.isBigDecimal()) {
            // Read exactly (an application mapper may read floats as BigDecimal): a whole value below 2^53 only.
            java.math.BigDecimal exact = id.decimalValue();
            if (exact.signum() == 0
                    || exact.stripTrailingZeros().scale() > 0
                    || exact.abs().compareTo(java.math.BigDecimal.valueOf(MAX_EXACT_DOUBLE)) >= 0) {
                return null;
            }
            return McpRequestKey.number(exact);
        }
        // A fractional id read as a double is matched by that double: a whole, non-zero value below 2^53 finds that
        // integer (7.0 finds 7, and so does an id within double rounding of 7); a fraction, zero (1e-400 underflows to
        // it), infinity, or a larger value has no key and cannot be cancelled. A BigDecimal node above is exact.
        double value = id.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value == 0 || Math.abs(value) >= MAX_EXACT_DOUBLE) {
            return null;
        }
        return McpRequestKey.number(java.math.BigDecimal.valueOf((long) value));
    }

    /** The largest magnitude below which every whole double is exact: 2^53. */
    private static final double MAX_EXACT_DOUBLE = 9_007_199_254_740_992d;

    /** The {@code reason} of a cancellation when it is a string; only ever logged. */
    private static String cancelReason(JsonNode reason) {
        return reason != null && reason.isString() ? reason.asString() : null;
    }

    private static ParsedArguments parseArguments(JsonNode arguments) {
        if (arguments == null) {
            return ParsedArguments.empty();
        }
        if (!arguments.isObject()) {
            return ParsedArguments.error(McpProtocol.ARGUMENTS_OBJECT_MESSAGE);
        }
        Set<String> names = new TreeSet<>();
        arguments.propertyNames().forEach(names::add);
        JsonNode query = arguments.get("query");
        if (query != null && !query.isString()) {
            return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("query", "a string"));
        }
        JsonNode id = arguments.get("id");
        if (id != null && !id.isString()) {
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
        if (scanId != null && !scanId.isString()) {
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
                query == null ? null : query.asString(),
                limit == null ? null : limit.asInt(),
                id == null ? null : id.asString(),
                names,
                null,
                scanId == null ? null : scanId.asString(),
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
        // McpDispatchOutcome is sealed; instanceof patterns (not a switch type pattern) keep this on
        // the project's Java 17 release level.
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
        } catch (RuntimeException | Error failure) {
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
