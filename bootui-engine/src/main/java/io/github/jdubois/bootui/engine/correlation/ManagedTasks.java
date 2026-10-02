package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.spi.CorrelationContext;

/**
 * Carries a request's correlation into the tasks it hands to a framework-managed executor ({@code docs/PLAN-v2.md} D30,
 * M4-15), such as an {@code @Async} method on Spring's auto-configured executor or a Quarkus {@code ManagedExecutor}
 * task. Each task becomes an execution of its own, with a fresh execution id, that keeps its request's id, so the work
 * it records is owned by the request that submitted it. BootUI's own work stays marked as BootUI's, so the journal still
 * leaves it out. Raw executors and {@code CompletableFuture} are left to the agent (M5-2).
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
        return submitted.withExecutionId("task-" + RequestIds.next());
    }

    /** {@code task}, run with the context of the thread submitting it now, as an execution of its request. */
    public static Runnable propagate(Runnable task) {
        CorrelationContext context = taskContext(BootUiCorrelation.current());
        if (task == null || context == null) {
            return task;
        }
        return () -> {
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(context)) {
                task.run();
            }
        };
    }
}
