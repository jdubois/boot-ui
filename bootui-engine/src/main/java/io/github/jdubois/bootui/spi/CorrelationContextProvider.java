package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral seam for the {@link CorrelationContext} of the work currently being recorded.
 *
 * <p>It supersedes {@link TraceIdProvider}: besides the trace id, it returns BootUI's own request identity, which
 * exists whether or not tracing is active. Recorders call it on the thread that does the work, so an implementation
 * reads thread-bound state and must never block. See {@code docs/PLAN-v2.md} §5.1.</p>
 */
@FunctionalInterface
public interface CorrelationContextProvider {

    /**
     * The context of the work being recorded; {@link CorrelationContext#NONE}, never {@code null}, when no request or
     * execution owns it. Implementations must be fully guarded, so a failing tracer never disrupts the recorded work.
     */
    CorrelationContext current();
}
