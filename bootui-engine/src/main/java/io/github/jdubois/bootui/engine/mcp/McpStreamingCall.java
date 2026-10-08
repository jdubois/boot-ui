package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.progress.OperationProgress;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One MCP 2026-07-28 {@code tools/call} answered on a request-scoped {@code text/event-stream}: progress
 * notifications related only to this request, then exactly one final JSON-RPC response.
 *
 * <p>Three threads touch a call, and each has one job. The <em>tool thread</em> runs the tool with an {@link
 * OperationProgress} bound; its reports only update the {@link McpProgressOutbox}, never perform I/O. The <em>writer
 * thread</em> is the only one that calls the {@link McpStreamSink}: it sends progress as the rate limit allows, a
 * heartbeat comment every {@link #HEARTBEAT_MILLIS} milliseconds, and then the final response. The <em>timeout
 * task</em> only changes the call's state. A slow or vanished client therefore never stalls a tool, the timeout
 * scheduler, or another call.
 *
 * <p>The call ends exactly once, through one atomic transition: the tool completes (with a result or a failure), the
 * absolute {@code bootui.mcp.execution-timeout} expires (final {@code -32002}, which modern rendering moves to {@code
 * -31002}), or the client disconnects ({@link #cancel()}, after which nothing more is written). Progress never extends
 * the timeout. A legacy (MCP 2025-06-18) call is different in one respect only: a closed stream ({@link
 * #clientClosed()}) stops the writer without cancelling, because that revision cancels only through {@code
 * notifications/cancelled}. The concurrency permit is released exactly once, when both the tool and the writer are done: a tool
 * holds it until it really returns, and a writer blocked on a client that stopped reading holds it too, so {@code
 * bootui.mcp.max-concurrent-calls} also bounds stalled streams. A part that never started is done when the call ends.
 */
public final class McpStreamingCall {

    /** Spacing of SSE keep-alive comments; also bounds how late a closed socket is noticed on a quiet stream. */
    public static final long HEARTBEAT_MILLIS = 2_000;

    private static final ScheduledThreadPoolExecutor TIMEOUTS = timeouts();
    private static final ExecutorService WRITERS = Executors.newCachedThreadPool(daemon("bootui-mcp-stream-"));

    private enum Lifecycle {
        CREATED,
        RUNNING,
        /** Ended before the tool started: the tool never runs. */
        NOT_STARTED
    }

    private enum EndKind {
        COMPLETED,
        TIMED_OUT,
        CANCELLED
    }

    private record End(EndKind kind, McpDispatchOutcome outcome) {}

    private final McpTool tool;
    private final McpArguments arguments;
    private final McpProgressToken progressToken;
    private final McpEra era;
    private final Runnable onFinished;
    private final Semaphore permits;
    private final McpRuntimeStats stats;
    private final McpFailureReporter failureReporter;
    private final ExecutorService toolExecutor;
    private final long createdAt = System.nanoTime();

    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.CREATED);
    private final AtomicReference<End> end = new AtomicReference<>();
    private final AtomicBoolean permitReleased = new AtomicBoolean();
    /** The tool and the writer: the permit is released when both are done. */
    private final AtomicInteger permitHolders = new AtomicInteger(2);

    private final AtomicBoolean writerClaimed = new AtomicBoolean();
    /** A legacy client closed the stream: the writer stops, but the call runs on (MCP 2025-06-18). */
    private volatile boolean clientGone;

    private final AtomicBoolean started = new AtomicBoolean();
    private final McpProgressOutbox outbox = new McpProgressOutbox(new McpProgressThrottle());
    private final OperationProgress progress;
    private final ScheduledFuture<?> timeoutTask;
    private volatile Future<?> toolFuture;

    McpStreamingCall(
            McpTool tool,
            McpArguments arguments,
            McpProgressToken progressToken,
            long timeoutMillis,
            Semaphore permits,
            McpRuntimeStats stats,
            McpFailureReporter failureReporter,
            ExecutorService toolExecutor) {
        this(
                tool,
                arguments,
                progressToken,
                McpEra.MODERN,
                () -> {},
                timeoutMillis,
                permits,
                stats,
                failureReporter,
                toolExecutor);
    }

    McpStreamingCall(
            McpTool tool,
            McpArguments arguments,
            McpProgressToken progressToken,
            McpEra era,
            Runnable onFinished,
            long timeoutMillis,
            Semaphore permits,
            McpRuntimeStats stats,
            McpFailureReporter failureReporter,
            ExecutorService toolExecutor) {
        this.tool = Objects.requireNonNull(tool, "tool");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
        this.progressToken = Objects.requireNonNull(progressToken, "progressToken");
        this.era = Objects.requireNonNull(era, "era");
        this.onFinished = Objects.requireNonNull(onFinished, "onFinished");
        this.permits = Objects.requireNonNull(permits, "permits");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.progress = new OperationProgress(outbox::offer);
        // Scheduled before the adapter can fail to start the call, so the permit is released in every case.
        this.timeoutTask = TIMEOUTS.schedule(this::timeOut, Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
    }

    /** The era of the request, which decides how its final response is rendered and what a closed stream means. */
    public McpEra era() {
        return era;
    }

    /** {@code true} once the call has ended and released its permit. */
    boolean finished() {
        return permitReleased.get();
    }

    /** The client's progress token, echoed by every notification of this call. */
    public McpProgressToken progressToken() {
        return progressToken;
    }

    /**
     * Starts the tool and the writer. Call it once, after the response headers are committed. A call that already
     * ended before it started still answers: a timeout or a failure is written as the stream's final response, and only
     * a cancelled call closes {@code sink} without one. A second start only closes {@code sink}.
     */
    public void start(McpStreamSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (!started.compareAndSet(false, true)) {
            sink.close();
            return;
        }
        if (!writerClaimed.compareAndSet(false, true)) {
            // The call ended before it started, and its permit is already released.
            End ended = end.get();
            if (ended == null || ended.kind() == EndKind.CANCELLED) {
                sink.close();
                return;
            }
            try {
                WRITERS.execute(() -> writeFinalOnly(sink, ended.outcome()));
            } catch (RuntimeException | Error rejected) {
                sink.close();
            }
            return;
        }
        try {
            WRITERS.execute(() -> write(sink));
        } catch (RuntimeException | Error failure) {
            // Recorded before closing the sink, whose close path may report a disconnect and hide the fault.
            fail(failure);
            sink.close();
            releasePart();
            return;
        }
        try {
            Future<?> future = toolExecutor.submit(this::runTool);
            toolFuture = future;
            if (endKind() == EndKind.CANCELLED || endKind() == EndKind.TIMED_OUT) {
                future.cancel(true);
            }
        } catch (RuntimeException | Error failure) {
            fail(failure);
        }
    }

    /**
     * The client closed the response stream. MCP 2026-07-28 makes that the cancellation of the request ({@link
     * #cancel()}). MCP 2025-06-18 says the opposite: "Disconnection SHOULD NOT be interpreted as the client cancelling
     * its request. To cancel, the client SHOULD explicitly send an MCP {@code CancelledNotification}." So a legacy call
     * only stops writing and runs on, bounded by the execution timeout. A legacy call whose stream never started has not
     * begun processing and has nowhere left to answer, so it is released at once like a modern one rather than held
     * until the timeout. Never blocks.
     */
    public void clientClosed() {
        if (era == McpEra.MODERN || !started.get()) {
            cancel();
        } else {
            clientGone = true;
            outbox.signal();
        }
    }

    /**
     * Cancels the request: a closed modern stream, or a legacy {@code notifications/cancelled}. Stops writing at once,
     * interrupts the tool, and is a no-op once the call has ended. Never blocks.
     */
    public void cancel() {
        end(EndKind.CANCELLED, null);
    }

    private void timeOut() {
        end(EndKind.TIMED_OUT, new ProtocolError(McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE));
    }

    private void fail(Throwable failure) {
        if (end(EndKind.COMPLETED, new ProtocolError(McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE))) {
            failureReporter.report("dispatching a request", failure);
        }
    }

    /**
     * A fault while writing the stream (not a client gone): it ends the call as a fault, reported even after the end,
     * and stops a tool still running, whose result can no longer be delivered and which no timeout bounds any more.
     */
    private void failWriting(Throwable failure) {
        if (end(EndKind.COMPLETED, new ProtocolError(McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE))) {
            failureReporter.report("dispatching a request", failure);
        } else {
            failureReporter.report("writing a stream", failure);
        }
        progress.cancel();
        Future<?> future = toolFuture;
        if (future != null) {
            future.cancel(true);
        }
    }

    private void runTool() {
        if (!lifecycle.compareAndSet(Lifecycle.CREATED, Lifecycle.RUNNING)) {
            return;
        }
        try {
            if (end.get() != null) {
                return;
            }
            Object payload = OperationProgress.runWith(progress, () -> tool.invoke(arguments));
            end(EndKind.COMPLETED, new ToolCallResult(payload));
        } catch (RuntimeException | Error failure) {
            McpDispatchOutcome expected = McpDispatcher.expectedToolFailure(failure);
            if (expected instanceof McpDispatchOutcome.Cancelled) {
                // Only a timeout or a disconnect stops a streaming tool, and each already ended the call.
                cancel();
            } else if (expected != null) {
                end(EndKind.COMPLETED, expected);
            } else {
                fail(failure);
            }
        } finally {
            releasePart();
        }
    }

    /** The one terminal transition; {@code true} for the caller that made it. */
    private boolean end(EndKind kind, McpDispatchOutcome outcome) {
        if (kind != EndKind.COMPLETED) {
            progress.cancel();
        }
        if (!end.compareAndSet(null, new End(kind, outcome))) {
            return false;
        }
        // Counted before anything is released or signalled, so whoever observes the end also sees its statistic.
        if (kind == EndKind.TIMED_OUT) {
            stats.recordTimeout();
        } else if (kind == EndKind.CANCELLED) {
            stats.recordCancellation();
        }
        if (kind != EndKind.TIMED_OUT) {
            timeoutTask.cancel(false);
        }
        Lifecycle before = lifecycle.getAndUpdate(state -> state == Lifecycle.CREATED ? Lifecycle.NOT_STARTED : state);
        if (writerClaimed.compareAndSet(false, true)) {
            // No writer will ever run for this call.
            releasePart();
        }
        if (before == Lifecycle.CREATED) {
            releasePart();
        } else if (kind != EndKind.COMPLETED) {
            Future<?> future = toolFuture;
            if (future != null) {
                future.cancel(true);
            }
        }
        outbox.signal();
        return true;
    }

    private void releasePart() {
        if (permitHolders.decrementAndGet() == 0 && permitReleased.compareAndSet(false, true)) {
            permits.release();
            stats.recordCall(System.nanoTime() - createdAt);
            onFinished.run();
        }
    }

    private EndKind endKind() {
        End current = end.get();
        return current == null ? null : current.kind();
    }

    private void write(McpStreamSink sink) {
        long nextHeartbeat = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MILLIS);
        try {
            while (true) {
                if (clientGone) {
                    return;
                }
                End current = end.get();
                if (current != null) {
                    if (current.kind() != EndKind.CANCELLED) {
                        for (ProgressEvent event : outbox.drainAll()) {
                            sink.progress(progressToken, event);
                        }
                        sink.complete(current.outcome());
                    }
                    return;
                }
                long untilHeartbeat = nextHeartbeat - System.nanoTime();
                if (untilHeartbeat <= 0) {
                    sink.heartbeat();
                    nextHeartbeat = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MILLIS);
                    continue;
                }
                ProgressEvent event = outbox.take(untilHeartbeat);
                if (event != null && endKind() != EndKind.CANCELLED && !clientGone) {
                    sink.progress(progressToken, event);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            clientClosed();
        } catch (IOException writeFailure) {
            // The client is gone or the stream broke: a modern call is cancelled, a legacy one runs on.
            clientClosed();
        } catch (RuntimeException | Error fault) {
            // A server fault (a rendering bug, a refused frame) is not the client going away.
            failWriting(fault);
        } finally {
            sink.close();
            releasePart();
        }
    }

    /** The final response of a call that ended before its stream started; it holds no permit any more. */
    private static void writeFinalOnly(McpStreamSink sink, McpDispatchOutcome outcome) {
        try {
            sink.complete(outcome);
        } catch (Exception | Error writeFailure) {
            // The client is gone; the call has already ended.
        } finally {
            sink.close();
        }
    }

    private static ScheduledThreadPoolExecutor timeouts() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, daemon("bootui-mcp-timeout-"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
