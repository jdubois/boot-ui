package io.github.jdubois.bootui.engine.mcp;

/** How {@link McpDispatcher#start} answers a request: one JSON response, or a request-scoped event stream. */
public sealed interface McpCallStart permits McpCallStart.Immediate, McpCallStart.Stream {

    /** Render {@code outcome} as the single JSON response, exactly as {@link McpDispatcher#dispatch} would. */
    record Immediate(McpDispatchOutcome outcome) implements McpCallStart {}

    /** Answer on {@code text/event-stream}: the adapter opens the stream and {@linkplain McpStreamingCall#start starts}
     * the call. */
    record Stream(McpStreamingCall call) implements McpCallStart {}
}
