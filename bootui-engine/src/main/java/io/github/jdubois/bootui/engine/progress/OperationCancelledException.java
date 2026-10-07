package io.github.jdubois.bootui.engine.progress;

/**
 * Thrown by {@link OperationProgress#checkCancelled()} when the caller abandoned the operation or its thread was
 * interrupted. It is an expected outcome, not a server fault: callers must not report it as a failure.
 */
public final class OperationCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OperationCancelledException() {
        super("Operation cancelled", null, false, false);
    }
}
