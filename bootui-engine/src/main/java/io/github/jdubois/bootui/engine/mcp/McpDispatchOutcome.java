package io.github.jdubois.bootui.engine.mcp;

import java.util.List;

/**
 * The typed outcome of an {@link McpDispatcher} evaluation. The adapter renders each variant to JSON,
 * echoing the original request id and serializing any tool payload with its own {@code ObjectMapper}.
 *
 * <p>Variants map directly to the JSON-RPC wire shape:
 *
 * <ul>
 *   <li>{@link NoResponse} — a notification; the transport emits no body (HTTP 202).
 *   <li>{@link InitializeResult}/{@link PingResult}/{@link ToolsListResult}/{@link ToolCallResult} —
 *       a JSON-RPC {@code result} envelope.
 *   <li>{@link ToolCallError} — a {@code result} carrying {@code isError:true} (an in-band tool
 *       failure the agent can read).
 *   <li>{@link ProtocolError} — a JSON-RPC {@code error} envelope ({@code code}/{@code message}).
 * </ul>
 */
public sealed interface McpDispatchOutcome
        permits McpDispatchOutcome.NoResponse,
                McpDispatchOutcome.InitializeResult,
                McpDispatchOutcome.PingResult,
                McpDispatchOutcome.ToolsListResult,
                McpDispatchOutcome.ToolCallResult,
                McpDispatchOutcome.ToolCallError,
                McpDispatchOutcome.ProtocolError {

    /** A notification: no response is emitted. */
    record NoResponse() implements McpDispatchOutcome {}

    /**
     * The {@code initialize} result.
     *
     * @param protocolVersion the negotiated protocol revision (requested, or the default)
     * @param serverName the advertised server name
     * @param serverVersion the advertised server version
     * @param instructions the advertised usage instructions (framework-specific copy)
     */
    record InitializeResult(String protocolVersion, String serverName, String serverVersion, String instructions)
            implements McpDispatchOutcome {}

    /** The {@code ping} result (an empty object). */
    record PingResult() implements McpDispatchOutcome {}

    /**
     * The {@code tools/list} result.
     *
     * @param tools the advertised tool descriptors, in catalog order
     */
    record ToolsListResult(List<McpToolDescriptor> tools) implements McpDispatchOutcome {}

    /**
     * A successful {@code tools/call}: the adapter serializes {@code payload} to a single text content
     * block with {@code isError:false}.
     *
     * @param payload the serializable tool result (typically a BootUI core DTO)
     */
    record ToolCallResult(Object payload) implements McpDispatchOutcome {}

    /**
     * A failed {@code tools/call} reported in-band ({@code isError:true}): a refused gate, a missing or
     * unknown tool, etc. The agent reads {@code message} as text content.
     *
     * @param message the human-readable failure reason
     */
    record ToolCallError(String message) implements McpDispatchOutcome {}

    /**
     * A JSON-RPC protocol error (an {@code error} envelope).
     *
     * @param code the JSON-RPC error code (see {@link McpProtocol})
     * @param message the human-readable error message
     */
    record ProtocolError(int code, String message) implements McpDispatchOutcome {}
}
