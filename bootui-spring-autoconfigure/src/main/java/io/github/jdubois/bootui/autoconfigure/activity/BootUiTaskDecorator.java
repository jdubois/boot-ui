package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.ManagedTasks;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the submitting request's correlation into each task of Spring Boot's auto-configured executor and scheduler
 * ({@code docs/PLAN-v2.md} D30, M4-15), so an {@code @Async} method's work is an execution of the request that called
 * it. It propagates BootUI's correlation only, never another context, so an application's own behavior is unchanged.
 */
public final class BootUiTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return ManagedTasks.propagate(runnable);
    }
}
