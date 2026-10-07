package io.github.jdubois.bootui.engine.progress;

/**
 * Receives an operation's accepted progress events on the reporting thread. Implementations must be a non-blocking
 * handoff (enqueue and return) and never perform I/O: the reporting thread is the operation itself, and {@link
 * OperationProgress} calls the listener under the lock {@link OperationProgress#cancel()} takes, so a listener that
 * blocked would also delay cancellation, the execution timeout, and the reply. A transport writes events from its own
 * thread.
 */
@FunctionalInterface
public interface ProgressListener {

    void onProgress(ProgressEvent event);
}
