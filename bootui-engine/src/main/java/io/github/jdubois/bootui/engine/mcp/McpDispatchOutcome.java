package io.github.jdubois.bootui.engine.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed outcome of an {@link McpDispatcher} evaluation. The adapter renders each variant to JSON,
 * echoing the original request id and serializing any tool payload with its own {@code ObjectMapper}.
 *
 * <p>Variants map directly to the JSON-RPC wire shape:
 *
 * <ul>
 *   <li>{@link NoResponse} — a notification; the transport emits no body (HTTP 202).
 *   <li>{@link InitializeResult}/{@link PingResult}/{@link ToolsListResult}/{@link PromptsListResult}/
 *       {@link PromptGetResult}/{@link ToolCallResult} — a JSON-RPC {@code result} envelope.
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
                McpDispatchOutcome.PromptsListResult,
                McpDispatchOutcome.PromptGetResult,
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

    /** The {@code prompts/list} result. */
    record PromptsListResult(List<McpPrompt> prompts) implements McpDispatchOutcome {}

    /** The {@code prompts/get} result. */
    record PromptGetResult(McpPrompt prompt) implements McpDispatchOutcome {}

    /**
     * A successful {@code tools/call}: the adapter serializes {@code payload} to a single text content
     * block with {@code isError:false}.
     *
     * @param payload the serializable tool result (typically a BootUI core DTO)
     */
    record ToolCallResult(Object payload) implements McpDispatchOutcome {}

    /**
     * Why a {@code tools/call} failed in-band. MCP renders every variant identically ({@code isError:true}
     * plus the message), but the {@code /bootui/api/cli} facade maps them to distinct HTTP statuses, so the
     * distinction is carried as data rather than recovered by matching on message text.
     */
    enum ToolErrorReason {
        /** The tool's backing panel is disabled. */
        PANEL_DISABLED,
        /** The tool is an action and its backing panel is read-only. */
        PANEL_READ_ONLY,
        /** Another invocation of the same action is already running. */
        ACTION_BUSY,
        /** The tool ran and reported a failure. */
        TOOL_FAILED
    }

    /**
     * A failed {@code tools/call} reported in-band ({@code isError:true}): a refused gate, a busy
     * single-flight action, or a client error the tool itself raised. The agent reads {@code message} as
     * text content.
     *
     * <p>{@code reason} and {@code status} answer two different questions, and a non-MCP transport needs
     * both. {@code reason} says why the <em>dispatcher</em> refused before the tool ran, over a closed
     * vocabulary the engine owns. {@code status} says which client error the <em>tool itself</em> asked
     * for once it was running, over the open 4xx range (see {@link McpToolClientException}); it is
     * {@code null} for every failure that is not a tool-raised client error. Both MCP codecs ignore both
     * fields, so the JSON-RPC wire shape is identical whether or not they are present.
     *
     * @param message the human-readable failure reason
     * @param reason the machine-readable cause, used by non-MCP transports to choose a status
     * @param status the canonical client-error status the tool asked for (e.g. {@code 404}), or {@code
     *     null} when the failure has no such status
     */
    record ToolCallError(String message, ToolErrorReason reason, Integer status) implements McpDispatchOutcome {

        /** A generic tool failure with no canonical client-error status. */
        public ToolCallError(String message) {
            this(message, ToolErrorReason.TOOL_FAILED, null);
        }

        /** A dispatcher-refused call: the tool never ran, so no tool-supplied status exists. */
        public ToolCallError(String message, ToolErrorReason reason) {
            this(message, reason, null);
        }

        /** A client error the tool raised while running. */
        public ToolCallError(String message, Integer status) {
            this(message, ToolErrorReason.TOOL_FAILED, status);
        }
    }

    /**
     * A JSON-RPC protocol error (an {@code error} envelope).
     *
     * @param code the JSON-RPC error code (see {@link McpProtocol})
     * @param message the human-readable error message; unexpected failures use the detail-free {@link
     *     McpProtocol#INTERNAL_ERROR_MESSAGE}
     * @param data the optional machine-readable {@code error.data} members, or {@code null}
     */
    record ProtocolError(int code, String message, Map<String, String> data) implements McpDispatchOutcome {

        public ProtocolError {
            data = data == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }

        /** An error without {@code data}. */
        public ProtocolError(int code, String message) {
            this(code, message, null);
        }
    }
}
