package io.github.jdubois.bootui.engine.correlation;

/**
 * The kinds of child execution a request's work is split into on other threads ({@code docs/PLAN-v2.md} D30, D32),
 * told apart by their execution id's prefix: {@value #TASK_PREFIX} for a task handed to a framework-managed executor
 * that BootUI decorates (M4-15), {@value #ASYNC_PREFIX} for a task the BootUI agent propagated through a JDK executor
 * (M5-2). Both keep their request's id, so their work is owned by the request.
 */
public final class ExecutionIds {

    /** A task of a framework-managed executor, propagated by BootUI's task decorator or context provider. */
    public static final String TASK_PREFIX = "task-";

    /** A task of a JDK executor, propagated by the BootUI agent. */
    public static final String ASYNC_PREFIX = "async-";

    private ExecutionIds() {}

    /** A new execution id for a managed task. */
    public static String nextTask() {
        return TASK_PREFIX + RequestIds.next();
    }

    /** A new execution id for a task the agent propagated. */
    public static String nextAsync() {
        return ASYNC_PREFIX + RequestIds.next();
    }

    /** Whether {@code executionId} names a task the agent propagated. */
    public static boolean isAsync(String executionId) {
        return executionId != null && executionId.startsWith(ASYNC_PREFIX);
    }

    /** Whether {@code executionId} names a managed task. */
    public static boolean isTask(String executionId) {
        return executionId != null && executionId.startsWith(TASK_PREFIX);
    }
}
