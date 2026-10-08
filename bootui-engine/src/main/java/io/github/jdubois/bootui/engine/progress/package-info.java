/**
 * Framework-neutral progress and cancellation for long-running BootUI operations.
 *
 * <p>An operation reads {@link io.github.jdubois.bootui.engine.progress.OperationProgress#current()} and reports
 * engine-authored {@link io.github.jdubois.bootui.engine.progress.ProgressPhase phases} with measured units of work,
 * and checks for cancellation between units. Outside an MCP call that asked for progress, the current progress is a
 * no-op that only honours thread interruption, so REST, CLI, and legacy MCP callers behave as before. Transports
 * (request-scoped SSE for MCP 2026-07-28) subscribe through a {@link
 * io.github.jdubois.bootui.engine.progress.ProgressListener}; the listener never performs I/O on the reporting thread.
 */
package io.github.jdubois.bootui.engine.progress;
