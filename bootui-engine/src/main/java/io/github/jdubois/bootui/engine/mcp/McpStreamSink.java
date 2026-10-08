package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import java.io.IOException;

/**
 * The adapter's side of a request-scoped MCP event stream. {@link McpStreamingCall} calls it from a single writer
 * thread, never from the tool thread, so an implementation may block on a slow client. Each method writes one SSE
 * event (or comment) and flushes it; a thrown exception means the client is gone and cancels the call.
 */
public interface McpStreamSink {

    /** Writes one {@code notifications/progress} for {@code token}. */
    void progress(McpProgressToken token, ProgressEvent event) throws IOException;

    /** Writes an SSE comment so the connection stays open and a closed socket is detected. */
    void heartbeat() throws IOException;

    /** Writes the final JSON-RPC response of the request; nothing follows it. */
    void complete(McpDispatchOutcome outcome) throws IOException;

    /** Ends the HTTP response. Called exactly once, last, whatever happened; must be idempotent and never throw. */
    void close();
}
