package io.github.jdubois.bootui.engine.mcp;

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

    /** Stable server name advertised in {@code initialize} and the panel status. */
    public static final String SERVER_NAME = "bootui";

    // JSON-RPC 2.0 error codes.
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
    /** Reported in-band when a {@code tools/call} omits the tool name. */
    public static final String MISSING_TOOL_NAME_MESSAGE = "Missing tool name";
    /** Reported in-band when a {@link McpToolSchema#ID} tool is called without a (non-blank) {@code id}. */
    public static final String MISSING_ID_ARGUMENT_MESSAGE = "Missing required argument: id";
    /** Fallback in-band tool-error text when a tool fails without a message. */
    public static final String TOOL_CALL_FAILED_MESSAGE = "Tool call failed";

    /** Transport-level message returned (JSON-RPC error {@link #SERVER_DISABLED}) while disabled. */
    public static final String SERVER_DISABLED_MESSAGE =
            "BootUI MCP server is disabled. Enable it from the MCP Server panel or set bootui.mcp.enabled=ON.";
}
