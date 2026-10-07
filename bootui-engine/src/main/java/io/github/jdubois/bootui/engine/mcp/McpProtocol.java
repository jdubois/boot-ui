package io.github.jdubois.bootui.engine.mcp;

import java.util.List;
import java.util.Set;

/**
 * Protocol-level constants and canonical messages shared by both adapters' MCP transports.
 *
 * <p>JSON serialization stays adapter-side (Jackson 3 on Spring, Jackson 2 on Quarkus), but the
 * protocol revision, server name, JSON-RPC error codes, and the exact human-readable messages are
 * single-sourced here so the two adapters answer byte-identically.
 */
public final class McpProtocol {

    private McpProtocol() {}

    /** MCP protocol revision advertised when the client does not request a specific one. */
    public static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    /**
     * Every legacy (initialization-based) protocol revision the BootUI MCP server understands. A legacy client
     * negotiates one of these through {@code initialize}.
     */
    public static final Set<String> KNOWN_VERSIONS = Set.of(DEFAULT_PROTOCOL_VERSION);

    /** The modern MCP revision, which carries its version, identity, and capabilities in every request's {@code _meta}. */
    public static final String MODERN_PROTOCOL_VERSION = "2026-07-28";

    /** Every modern (per-request metadata) protocol revision the BootUI MCP server understands. */
    public static final Set<String> MODERN_VERSIONS = Set.of(MODERN_PROTOCOL_VERSION);

    /** Every revision the server supports, newest first, as advertised by {@code server/discover} and version errors. */
    public static final List<String> SUPPORTED_VERSIONS = List.of(MODERN_PROTOCOL_VERSION, DEFAULT_PROTOCOL_VERSION);

    /** HTTP header carrying the negotiated protocol revision on post-initialization requests. */
    public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    /** Modern HTTP header mirroring the JSON-RPC {@code method}. */
    public static final String METHOD_HEADER = "Mcp-Method";
    /** Modern HTTP header mirroring {@code params.name} of {@code tools/call} and {@code prompts/get}. */
    public static final String NAME_HEADER = "Mcp-Name";

    /** Required modern {@code _meta} key naming the request's protocol revision. */
    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    /** Required modern {@code _meta} key carrying the client's capabilities for the request. */
    public static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    /** Modern result {@code _meta} key identifying the server. */
    public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    /** Request {@code _meta} key opting the request into progress notifications. */
    public static final String META_PROGRESS_TOKEN = "progressToken";

    /** Request header listing the media types the client accepts. */
    public static final String ACCEPT_HEADER = "Accept";
    /** Media type of a request-scoped MCP event stream. */
    public static final String EVENT_STREAM_MEDIA_TYPE = "text/event-stream";
    /** Response header that tells reverse proxies such as nginx not to buffer the event stream. */
    public static final String ACCEL_BUFFERING_HEADER = "X-Accel-Buffering";
    /** Prefix of the line carrying one JSON-RPC message in an SSE event; every stack writes it without a space. */
    public static final String SSE_DATA_PREFIX = "data:";
    /** Terminator of one SSE event. */
    public static final String SSE_EVENT_END = "\n\n";
    /** An SSE comment: a keep-alive that clients ignore. */
    public static final String SSE_HEARTBEAT = ":\n\n";
    /** JSON-RPC method of a progress notification. */
    public static final String PROGRESS_NOTIFICATION = "notifications/progress";

    /** The modern {@code resultType} of every final result BootUI returns. */
    public static final String RESULT_TYPE_COMPLETE = "complete";
    /**
     * Freshness hint of the cacheable modern results ({@code server/discover}, {@code tools/list}, {@code prompts/list}).
     * The catalog is fixed for the life of the JVM, but a DevTools restart can change it.
     */
    public static final long CACHE_TTL_MILLIS = 60_000;
    /**
     * Cache scope of the cacheable modern results: BootUI is a local, possibly token-protected endpoint whose catalog and
     * instructions describe one application, so a shared cache must never keep them.
     */
    public static final String CACHE_SCOPE = "private";

    /** JSON-RPC version string required in every request. */
    public static final String JSONRPC_VERSION = "2.0";

    /** Stable server name advertised in {@code initialize} and the panel status. */
    public static final String SERVER_NAME = "bootui";

    /** Default maximum request payload size in bytes (1 MiB). */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;

    /** Default maximum concurrent MCP tool invocations. */
    public static final int DEFAULT_MAX_CONCURRENT_CALLS = 20;
    /** Default maximum duration of one MCP tool invocation. */
    public static final int DEFAULT_EXECUTION_TIMEOUT_MILLIS = 30_000;
    /** Default maximum rendered MCP response size (4 MiB). */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    // JSON-RPC 2.0 error codes.
    /** JSON parsing failed. */
    public static final int PARSE_ERROR = -32700;
    /** Request object is invalid ({@code jsonrpc} missing, batch not supported, etc.). */
    public static final int INVALID_REQUEST = -32600;
    /** Method is not recognized ({@code tools/call} etc.). */
    public static final int METHOD_NOT_FOUND = -32601;
    /** Request shape or parameters are invalid. */
    public static final int INVALID_PARAMS = -32602;
    /** An unexpected runtime failure occurred while handling the request. */
    public static final int INTERNAL_ERROR = -32603;
    /** Server-defined: the MCP server is currently disabled (transport-level short-circuit). */
    public static final int SERVER_DISABLED = -32000;
    /** Server-defined: the aggregate MCP tool-call capacity is currently exhausted. */
    public static final int SERVER_AT_CAPACITY = -32001;
    /** Server-defined: a tool exceeded its execution-time budget. */
    public static final int TOOL_TIMEOUT = -32002;
    /** Server-defined: a rendered response exceeded its byte budget. */
    public static final int RESPONSE_TOO_LARGE = -32003;
    /**
     * BootUI-defined, outside the JSON-RPC reserved range: the caller cancelled the request. The code is the one the
     * Language Server Protocol uses for the same outcome.
     */
    public static final int REQUEST_CANCELLED = -32800;
    /** Reported when the caller cancelled the request. */
    public static final String REQUEST_CANCELLED_MESSAGE = "MCP request cancelled";

    // MCP 2026-07-28 protocol error codes.
    /** The HTTP headers do not match the request body, or a required header is missing or malformed. */
    public static final int HEADER_MISMATCH = -32020;
    /** The request names a protocol revision the server does not support. */
    public static final int UNSUPPORTED_PROTOCOL_VERSION_ERROR = -32022;

    /**
     * Offset that moves BootUI's server-defined codes out of the JSON-RPC reserved range for modern clients.
     * MCP 2026-07-28 says new implementations SHOULD NOT use {@code -32000..-32019} and MUST NOT emit {@code -32002};
     * the engine, the CLI facade, and legacy clients keep the original codes.
     */
    private static final int MODERN_SERVER_ERROR_OFFSET = 1_000;

    /** Returned to a modern client whose request names an unsupported protocol revision. */
    public static final String UNSUPPORTED_MODERN_PROTOCOL_VERSION_MESSAGE = "Unsupported protocol version";
    /** Returned when the {@code MCP-Protocol-Version} header is missing, repeated, or differs from {@code _meta}. */
    public static final String PROTOCOL_VERSION_HEADER_MISMATCH_MESSAGE =
            "Header mismatch: MCP-Protocol-Version must be sent once and match _meta " + META_PROTOCOL_VERSION;
    /** Returned when the {@code Mcp-Method} header is missing, repeated, or differs from the body. */
    public static final String METHOD_HEADER_MISMATCH_MESSAGE =
            "Header mismatch: Mcp-Method must be sent once and match the request method";
    /** Returned when the {@code Mcp-Name} header is missing, repeated, malformed, or differs from the body. */
    public static final String NAME_HEADER_MISMATCH_MESSAGE =
            "Header mismatch: Mcp-Name must be sent once and match params.name";
    /** Returned when a modern request's protocol version is not a string. */
    public static final String META_PROTOCOL_VERSION_TYPE_MESSAGE =
            "_meta " + META_PROTOCOL_VERSION + " must be a string";
    /** Returned when a request carries a modern {@code MCP-Protocol-Version} header but no modern {@code _meta}. */
    public static final String MISSING_META_PROTOCOL_VERSION_MESSAGE =
            "Missing required _meta field: " + META_PROTOCOL_VERSION;
    /** Returned when a modern request omits its client capabilities. */
    public static final String MISSING_META_CLIENT_CAPABILITIES_MESSAGE =
            "Missing required _meta field: " + META_CLIENT_CAPABILITIES;
    /** Returned when a modern request's client capabilities are not an object. */
    public static final String META_CLIENT_CAPABILITIES_TYPE_MESSAGE =
            "_meta " + META_CLIENT_CAPABILITIES + " must be an object";
    /** Returned when a progress token is neither a string nor an integer. */
    public static final String PROGRESS_TOKEN_TYPE_MESSAGE = "_meta progressToken must be a string or an integer";

    /**
     * The JSON-RPC error code to put on the wire for {@code era}: modern clients receive BootUI's server-defined codes
     * moved out of the reserved range, legacy clients receive them unchanged, and every standard or MCP-defined code
     * is the same for both eras.
     */
    public static int wireErrorCode(McpEra era, int code) {
        if (era == McpEra.MODERN && code <= SERVER_DISABLED && code >= RESPONSE_TOO_LARGE) {
            return code + MODERN_SERVER_ERROR_OFFSET;
        }
        return code;
    }

    /**
     * The HTTP status of a JSON-RPC error response for {@code era}. Modern Streamable HTTP answers an unknown method
     * with {@code 404}; legacy keeps answering every in-band error with {@code 200}.
     */
    public static int errorHttpStatus(McpEra era, int code) {
        return era == McpEra.MODERN && code == METHOD_NOT_FOUND ? 404 : 200;
    }

    /** The HTTP status of the JSON response carrying {@code outcome} for {@code era}, derived from the outcome itself. */
    public static int httpStatus(McpEra era, McpDispatchOutcome outcome) {
        return outcome instanceof McpDispatchOutcome.ProtocolError error ? errorHttpStatus(era, error.code()) : 200;
    }

    /** Returned when the request is not a JSON-RPC object. */
    public static final String MALFORMED_REQUEST_MESSAGE = "Request must be a JSON-RPC object";
    /** Returned when a request omits {@code jsonrpc: "2.0"}. */
    public static final String MISSING_JSONRPC_MESSAGE = "Request must include jsonrpc: \"2.0\"";
    /** Returned when a (non-notification) request omits {@code method}. */
    public static final String MISSING_METHOD_MESSAGE = "Missing 'method'";
    /** Returned when the transport receives a batch request, which MCP Streamable HTTP forbids. */
    public static final String BATCH_NOT_SUPPORTED_MESSAGE =
            "JSON-RPC batch requests are not supported by MCP Streamable HTTP transport";
    /** Returned when the HTTP protocol-version header names an unsupported revision. */
    public static final String UNSUPPORTED_PROTOCOL_VERSION_MESSAGE = "Unsupported MCP-Protocol-Version";
    /** Reported in-band when a {@code tools/call} omits the tool name. */
    public static final String MISSING_TOOL_NAME_MESSAGE = "Missing tool name";
    /** Returned when a {@code prompts/get} request omits the prompt name. */
    public static final String MISSING_PROMPT_NAME_MESSAGE = "Missing prompt name";
    /** Reported in-band when a {@link McpToolSchema#ID} tool is called without a (non-blank) {@code id}. */
    public static final String MISSING_ID_ARGUMENT_MESSAGE = "Missing required argument: id";
    /** Returned when advisor detail retrieval is not tied to a completed snapshot. */
    public static final String MISSING_SCAN_ID_ARGUMENT_MESSAGE = "Missing required argument: scanId";
    /** Returned when {@code tools/call.params.arguments} is present but is not a JSON object. */
    public static final String ARGUMENTS_OBJECT_MESSAGE = "Tool arguments must be an object";
    /** Returned when a JSON-RPC id is not a string, number, or null. */
    public static final String INVALID_ID_MESSAGE = "Request id must be a string, number, or null";
    /** Returned when JSON-RPC params is present but is not an object. */
    public static final String PARAMS_OBJECT_MESSAGE = "Request params must be an object";
    /** Fallback in-band tool-error text when a tool fails without a message. */
    public static final String TOOL_CALL_FAILED_MESSAGE = "Tool call failed";
    /** Standard JSON-RPC message for an unexpected server-side failure. */
    public static final String INTERNAL_ERROR_MESSAGE = "Internal error";
    /** Reported when the server refuses another concurrent {@code tools/call}. */
    public static final String RATE_LIMITED_MESSAGE = "MCP server is at capacity; try again shortly.";
    /** Reported when a tool exceeds its execution-time budget. */
    public static final String TOOL_TIMEOUT_MESSAGE = "MCP tool execution timed out";
    /** Reported when a rendered response exceeds its byte budget. */
    public static final String RESPONSE_TOO_LARGE_MESSAGE = "MCP response exceeds configured byte limit";

    /** Transport-level message returned (JSON-RPC error {@link #SERVER_DISABLED}) while disabled. */
    public static final String SERVER_DISABLED_MESSAGE =
            "BootUI MCP server is disabled. Enable it from the MCP Server panel or set bootui.mcp.enabled=ON.";

    /** Prefix of the in-band message reported when a {@code tools/call} names a tool this server has not registered. */
    public static final String UNKNOWN_TOOL_PREFIX = "Unknown tool: ";

    /** Canonical message reported when a {@code tools/call} names a tool this server has not registered. */
    public static String unknownToolMessage(String name) {
        return UNKNOWN_TOOL_PREFIX + name;
    }

    /**
     * Prefix of the message reported when a {@code tools/call} names a BootUI tool this server does not advertise,
     * because its panel is unavailable in this application or on this stack.
     */
    public static final String UNAVAILABLE_TOOL_PREFIX = "Tool not available in this application: ";

    /**
     * Canonical message for a BootUI tool this server does not advertise: the tool, then why, in the panel's own words
     * when it gives a reason.
     *
     * @param name the tool name
     * @param panelTitle the title of the panel backing it
     * @param reason why that panel is unavailable here, or {@code null} when unknown
     * @param stacks the request stacks that advertise the tool
     */
    public static String unavailableToolMessage(
            String name, String panelTitle, String reason, Set<McpToolCatalog.Stack> stacks) {
        StringBuilder message =
                new StringBuilder(UNAVAILABLE_TOOL_PREFIX).append(name).append(". ");
        if (reason != null && !reason.isBlank()) {
            String trimmed = reason.trim();
            message.append("Its ")
                    .append(panelTitle)
                    .append(" panel is unavailable: ")
                    .append(trimmed)
                    .append(".!?".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0 ? "" : ".");
        } else if (stacks.size() < McpToolCatalog.Stack.values().length) {
            message.append("Only ")
                    .append(String.join(
                            " and ",
                            stacks.stream().sorted().map(McpProtocol::stackName).toList()))
                    .append(stacks.size() == 1 ? " advertises it." : " advertise it.");
        } else {
            message.append("Its ").append(panelTitle).append(" panel does not provide it in this application.");
        }
        return message.toString();
    }

    private static String stackName(McpToolCatalog.Stack stack) {
        return switch (stack) {
            case SPRING_MVC -> "Spring MVC";
            case SPRING_WEBFLUX -> "Spring WebFlux";
            case QUARKUS -> "Quarkus";
        };
    }

    /**
     * {@link #MISSING_ID_ARGUMENT_MESSAGE} or {@link #MISSING_SCAN_ID_ARGUMENT_MESSAGE}, followed by where the tool's id
     * comes from, so the error names the call to make first.
     */
    public static String missingArgumentMessage(String message, String toolName) {
        McpToolGuide.IdSource source = McpToolGuide.idSource(toolName);
        return source == null ? message : message + " (" + source.describe() + ")";
    }

    /**
     * {@code true} when one of the {@code Accept} header values lists {@code text/event-stream} explicitly with a
     * non-zero quality. A wildcard does not count: a client must say it can read a stream before it gets one.
     */
    public static boolean acceptsEventStream(List<String> acceptHeaders) {
        if (acceptHeaders == null) {
            return false;
        }
        for (String header : acceptHeaders) {
            if (header == null) {
                continue;
            }
            for (String range : header.split(",")) {
                String[] parts = range.split(";");
                if (!EVENT_STREAM_MEDIA_TYPE.equalsIgnoreCase(parts[0].trim())) {
                    continue;
                }
                double quality = 1;
                for (int i = 1; i < parts.length; i++) {
                    String parameter = parts[i].trim();
                    if (parameter.length() > 2 && parameter.substring(0, 2).equalsIgnoreCase("q=")) {
                        try {
                            quality = Double.parseDouble(parameter.substring(2).trim());
                        } catch (NumberFormatException malformed) {
                            quality = 0;
                        }
                    }
                }
                if (quality > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Canonical invalid-type message used by both adapter codecs. */
    public static String invalidArgumentTypeMessage(String name, String expectedType) {
        return "Argument '" + name + "' must be " + expectedType;
    }

    /** Canonical lower-bound message used by both adapter codecs. */
    public static String invalidArgumentMinimumMessage(String name, int minimum) {
        return "Argument '" + name + "' must be at least " + minimum;
    }
}
