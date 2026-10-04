package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.ManagedTasks;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the submitting request's correlation into each task of Spring Boot's auto-configured executor and scheduler
 * ({@code docs/PLAN-v2.md} D30, M4-15), so an {@code @Async} method's work is an execution of the request that called
 * it. It propagates BootUI's correlation only, never another context, so an application's own behavior is unchanged.
 *
 * <p>Spring Boot composes every {@code TaskDecorator} bean in order, the first applied innermost. This one is first, so
 * it sits next to the task, as when it is composed inside the application's own, and sees a scheduler's raw future.
 */
public final class BootUiTaskDecorator implements TaskDecorator, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        return ManagedTasks.propagate(runnable);
    }
}
