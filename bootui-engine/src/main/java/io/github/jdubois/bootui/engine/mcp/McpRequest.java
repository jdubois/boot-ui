package io.github.jdubois.bootui.engine.mcp;

/**
 * A neutral, already-parsed view of a JSON-RPC request, built by each adapter's envelope codec and
 * consumed by {@link McpDispatcher}.
 *
 * <p>The codec performs all JSON work (deciding {@code notification} from the presence of an id,
 * extracting {@code params}, and pulling the {@code tools/call} name + raw {@code query}/{@code
 * limit}/{@code id} arguments). The top-level JSON-RPC message id (used to correlate a response with
 * its request) is <em>not</em> carried here: the codec pairs the returned {@link McpDispatchOutcome}
 * with the original message-id node when it renders the response. {@link #rawId} below is a different,
 * tool-specific concept: the {@code arguments.id} of an {@link McpToolSchema#ID} tool call (e.g. which
 * exception group to fetch detail for).
 *
 * @param jsonrpc the top-level JSON-RPC version string (typically {@code "2.0"})
 * @param method the JSON-RPC method (possibly blank; the dispatcher decides what to do)
 * @param notification {@code true} when the request carried no id (no response is emitted)
 * @param requestedProtocolVersion the client {@code params.protocolVersion} for {@code initialize}
 *     (may be {@code null}/blank → the default is advertised)
 * @param toolName the {@code params.name} for {@code tools/call} (may be {@code null}/blank)
 * @param rawQuery the client {@code arguments.query} as parsed (may be {@code null}/blank/untrimmed)
 * @param rawLimit the client {@code arguments.limit} as parsed (may be {@code null} or out of range)
 * @param rawId the client {@code arguments.id} as parsed (may be {@code null}/blank/untrimmed)
 */
public record McpRequest(
        String jsonrpc,
        String method,
        boolean notification,
        String requestedProtocolVersion,
        String toolName,
        String rawQuery,
        Integer rawLimit,
        String rawId) {}
