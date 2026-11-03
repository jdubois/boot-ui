package io.github.jdubois.bootui.engine.threads;

/** Expected diagnostic failure mapped identically by HTTP and MCP adapters. */
public final class ThreadAnalysisException extends RuntimeException {
    private final int status;

    public ThreadAnalysisException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
