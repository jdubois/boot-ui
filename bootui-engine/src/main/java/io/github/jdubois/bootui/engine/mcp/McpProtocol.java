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
    /** Fallback in-band tool-error text when a tool fails without a message. */
    public static final String TOOL_CALL_FAILED_MESSAGE = "Tool call failed";
    /** Reported when the server refuses another concurrent {@code tools/call}. */
    public static final String RATE_LIMITED_MESSAGE = "MCP server is at capacity; try again shortly.";

    /** Transport-level message returned (JSON-RPC error {@link #SERVER_DISABLED}) while disabled. */
    public static final String SERVER_DISABLED_MESSAGE =
            "BootUI MCP server is disabled. Enable it from the MCP Server panel or set bootui.mcp.enabled=ON.";
}
