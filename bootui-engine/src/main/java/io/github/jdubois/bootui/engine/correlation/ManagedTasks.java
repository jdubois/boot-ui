package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Carries a request's correlation into the tasks it hands to a framework-managed executor ({@code docs/PLAN-v2.md} D30,
 * M4-15), such as an {@code @Async} method on Spring's auto-configured executor or a Quarkus {@code ManagedExecutor}
 * task. Each task becomes an execution of its own, with a fresh execution id, that keeps its request's id, so the work
 * it records is owned by the request that submitted it. BootUI's own work stays marked as BootUI's, so the journal still
 * leaves it out. Raw executors and {@code CompletableFuture} are left to the agent (M5-2).
 *
 * <p>A task that runs on a thread where its request is already current, such as a caller-runs rejection or a direct
 * executor, reuses that context rather than opening an empty nested execution.</p>
 */
public final class ManagedTasks {

    private ManagedTasks() {}

    /** The context a task submitted under {@code submitted} runs with, or {@code null} when it carries nothing. */
    public static CorrelationContext taskContext(CorrelationContext submitted) {
        if (submitted == null || submitted.isEmpty()) {
            return null;
        }
        if (submitted.bootUi() || submitted.requestId() == null) {
            return submitted;
        }
        return submitted.withExecutionId(ExecutionIds.nextTask());
    }

    /**
     * Opens {@code context} for a task about to run on this thread, or returns {@code null} when the thread already
     * works for the context's request, which the task then keeps.
     *
     * @param context the task's context, from {@link #taskContext}; {@code null} clears the thread's context
     */
    public static BootUiCorrelation.Scope open(CorrelationContext context) {
        if (context != null
                && context.requestId() != null
                && context.requestId().equals(BootUiCorrelation.current().requestId())) {
            return null;
        }
        // Clearing is explicit rather than merely absent, so an adapter with an ambient context honours it.
        return context == null ? BootUiCorrelation.openCleared() : BootUiCorrelation.open(context);
    }

    /**
     * {@code task}, run with the context of the thread submitting it now, as an execution of its request. Only its first
     * run is: a periodic task a scheduler decorates once and runs again and again belongs to the request that scheduled
     * it once, not on every later run, which runs with no request. A task run on another thread is always its own
     * execution, even when an application decorator composed around it already restored the request there; only a
     * caller-runs task, on the submitting thread itself, keeps the request's context.
     */
    public static Runnable propagate(Runnable task) {
        CorrelationContext context = taskContext(BootUiCorrelation.current());
        if (task == null || context == null) {
            return task;
        }
        AtomicBoolean first = new AtomicBoolean(true);
        Thread submitter = Thread.currentThread();
        return () -> {
            BootUiCorrelation.Scope scope;
            if (!first.getAndSet(false)) {
                scope = open(null);
            } else if (Thread.currentThread() == submitter) {
                scope = open(context);
            } else {
                scope = BootUiCorrelation.open(context);
            }
            try {
                task.run();
            } finally {
                if (scope != null) {
                    scope.close();
                }
            }
        };
    }
}
