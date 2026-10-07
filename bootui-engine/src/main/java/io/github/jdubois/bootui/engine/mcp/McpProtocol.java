package io.github.jdubois.bootui.engine.mcp;

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

    /** Every protocol revision the BootUI MCP server understands. */
    public static final Set<String> KNOWN_VERSIONS = Set.of(DEFAULT_PROTOCOL_VERSION);

    /** HTTP header carrying the negotiated protocol revision on post-initialization requests. */
    public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

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

    /** Returned when the request is not a JSON-RPC object. */
    public static final String MALFORMED_REQUEST_MESSAGE = "Request must be a JSON-RPC object";
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

    /** Canonical invalid-type message used by both adapter codecs. */
    public static String invalidArgumentTypeMessage(String name, String expectedType) {
        return "Argument '" + name + "' must be " + expectedType;
    }

    /** Canonical lower-bound message used by both adapter codecs. */
    public static String invalidArgumentMinimumMessage(String name, int minimum) {
        return "Argument '" + name + "' must be at least " + minimum;
    }
}
