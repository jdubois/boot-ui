package io.github.jdubois.bootui.engine.progress;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Progress and cancellation of the operation running on the current thread.
 *
 * <p>The MCP dispatcher binds one instance to the tool thread for a call that asked for progress ({@link #runWith}).
 * Everywhere else {@link #current()} is {@link #NONE}, which ignores reports and is cancelled only when the thread is
 * interrupted, so an operation never branches on who called it.
 *
 * <p>Reports are filtered before they reach the listener: a non-finite or non-increasing {@code completed} is
 * dropped, a non-finite or non-positive {@code total} is omitted, and nothing is reported once the operation is
 * cancelled.
 */
public final class OperationProgress {

    /** No progress listener; cancelled only by thread interruption. */
    public static final OperationProgress NONE = new OperationProgress(null);

    private static final ThreadLocal<OperationProgress> CURRENT = new ThreadLocal<>();

    private final ProgressListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private double lastProgress = Double.NEGATIVE_INFINITY;

    /** Progress delivered to {@code listener}, or no progress when it is {@code null}. */
    public OperationProgress(ProgressListener listener) {
        this.listener = listener;
    }

    /** The progress bound to the current thread, or {@link #NONE}. */
    public static OperationProgress current() {
        OperationProgress progress = CURRENT.get();
        return progress == null ? NONE : progress;
    }

    /** Runs {@code work} on the current thread with {@code progress} bound, restoring the previous binding after. */
    public static <T> T runWith(OperationProgress progress, Supplier<T> work) {
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(work, "work");
        OperationProgress previous = CURRENT.get();
        CURRENT.set(progress);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** {@code true} when a listener receives this operation's reports. */
    public boolean reportsProgress() {
        return listener != null;
    }

    /**
     * Reports that {@code completed} of {@code total} units are done and the operation is in {@code phase}.
     *
     * @param total the total units, or zero or less when unknown
     */
    public void report(ProgressPhase phase, double completed, double total) {
        Objects.requireNonNull(phase, "phase");
        if (listener == null || cancelled() || !Double.isFinite(completed)) {
            return;
        }
        Double reportedTotal = Double.isFinite(total) && total > 0 ? total : null;
        synchronized (this) {
            if (completed <= lastProgress) {
                return;
            }
            lastProgress = completed;
        }
        listener.onProgress(new ProgressEvent(completed, reportedTotal, phase.label()));
    }

    /** Marks the operation cancelled; later reports are dropped and {@link #checkCancelled()} throws. */
    public void cancel() {
        if (this != NONE) {
            cancelled.set(true);
        }
    }

    /** {@code true} once the operation was cancelled or its thread interrupted (the interrupt flag is kept). */
    public boolean cancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    /** Throws {@link OperationCancelledException} when {@link #cancelled()}; call it between units of work. */
    public void checkCancelled() {
        if (cancelled()) {
            throw new OperationCancelledException();
        }
    }
}
