package io.github.jdubois.bootui.engine.mcp;

/**
 * A caller's handle on one dispatched request, through which another thread can cancel the tool call it runs.
 *
 * <p>{@link McpDispatcher#dispatch(McpRequest, McpCancellation)} attaches the running invocation to it. {@link
 * #cancel()} is idempotent and safe from any thread, before, during, or after the call: before, the call is cancelled as
 * soon as it starts; during, the tool's {@link io.github.jdubois.bootui.engine.progress.OperationProgress} is cancelled
 * and its thread interrupted; after, it does nothing.
 */
public final class McpCancellation {

    private boolean cancelled;
    private Runnable target;

    /** Cancels the request; does nothing once it has ended or was already cancelled. */
    public void cancel() {
        Runnable running;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            running = target;
        }
        if (running != null) {
            running.run();
        }
    }

    /** {@code true} once {@link #cancel()} was called. */
    public synchronized boolean cancelled() {
        return cancelled;
    }

    /** Attaches the running call; returns {@code false} when it was already cancelled, so the caller stops it now. */
    synchronized boolean attach(Runnable cancelRunning) {
        if (cancelled) {
            return false;
        }
        target = cancelRunning;
        return true;
    }

    synchronized void detach() {
        target = null;
    }
}
