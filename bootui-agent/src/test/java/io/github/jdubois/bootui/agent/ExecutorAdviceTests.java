package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.TaskPropagation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExecutorAdviceTests {

    private final ThreadLocal<String> owner = new ThreadLocal<>();
    private final Supplier<Object> capture = () -> owner.get() == null ? null : new Object[] {owner.get()};
    private final Function<Object, AutoCloseable> reopen = call -> () -> {};

    @AfterEach
    void release() {
        AgentBridge.release("advice-test", "dev");
        owner.remove();
    }

    @Test
    void failedThreadPerTaskStartReleasesOnlyTheTouchedSubmission() {
        claim();
        Runnable task = () -> {};
        owner.set("A");
        boolean keyed = ExecutorAdvice.ThreadPerTask.enter(task);
        assertThat(keyed).isTrue();
        ExecutorAdvice.ThreadPerTask.exit(keyed, task, new IllegalThreadStateException());
        owner.set("B");
        boolean accepted = ExecutorAdvice.ThreadPerTask.enter(task);
        ExecutorAdvice.ThreadPerTask.exit(accepted, task, null);

        Object handle = TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER);
        assertThat(handle).isNotNull();
        TaskPropagation.exit(handle, null);
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER))
                .isNull();
    }

    @Test
    void anUntouchedFailedStartDoesNotReleaseAnotherPendingSubmission() {
        claim();
        Runnable task = () -> {};
        owner.set("A");
        ExecutorAdvice.ThreadPerTask.enter(task);
        ExecutorAdvice.ThreadPerTask.exit(false, task, new IllegalThreadStateException());

        Object handle = TaskPropagation.enter(task, TaskPropagation.APPLY_RUN_WORKER);
        assertThat(handle).isNotNull();
        TaskPropagation.exit(handle, null);
    }

    @Test
    void rejectedForkJoinRootReleasesItsSubmissionBeforeAnotherOwnerResubmits() {
        claim();
        RecursiveAction task = new RecursiveAction() {
            @Override
            protected void compute() {}
        };
        owner.set("A");
        boolean keyed = ExecutorAdvice.ForkJoinRoot.enter(ForkJoinPool.commonPool(), task);
        ExecutorAdvice.ForkJoinRoot.exit(keyed, task, new RejectedExecutionException());
        owner.set("B");
        ExecutorAdvice.ForkJoinRoot.enter(ForkJoinPool.commonPool(), task);

        Object handle = TaskPropagation.enter(task, TaskPropagation.APPLY_DO_EXEC);
        assertThat(handle).isNotNull();
        TaskPropagation.exit(handle, null);
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_DO_EXEC)).isNull();
    }

    @Test
    void aRejectedCompletedForkJoinRootStillReleasesItsSubmission() {
        claim();
        RecursiveAction task = new RecursiveAction() {
            @Override
            protected void compute() {}
        };
        task.complete(null);
        owner.set("A");
        boolean keyed = ExecutorAdvice.ForkJoinRoot.enter(ForkJoinPool.commonPool(), task);
        ExecutorAdvice.ForkJoinRoot.exit(keyed, task, new RejectedExecutionException());
        task.reinitialize();
        owner.set("B");
        ExecutorAdvice.ForkJoinRoot.enter(ForkJoinPool.commonPool(), task);

        Object handle = TaskPropagation.enter(task, TaskPropagation.APPLY_DO_EXEC);
        assertThat(handle).isNotNull();
        TaskPropagation.exit(handle, null);
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_DO_EXEC)).isNull();
    }

    @Test
    void successfulAdmissionDoesNotReleaseAnotherPendingSubmission() {
        claim();
        RecursiveAction task = new RecursiveAction() {
            @Override
            protected void compute() {}
        };
        owner.set("A");
        ExecutorAdvice.ForkJoinRoot.enter(ForkJoinPool.commonPool(), task);
        owner.set("B");
        boolean keyed = ExecutorAdvice.ForkJoinRoot.enter(ForkJoinPool.commonPool(), task);
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_DO_EXEC)).isNull();
        task.completeExceptionally(new RejectedExecutionException("application failure"));
        ExecutorAdvice.ForkJoinRoot.exit(keyed, task, null);
        owner.set("C");
        ExecutorAdvice.ForkJoinRoot.enter(ForkJoinPool.commonPool(), task);

        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_DO_EXEC)).isNull();
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_DO_EXEC)).isNull();
        assertThat(TaskPropagation.enter(task, TaskPropagation.APPLY_DO_EXEC)).isNull();
    }

    private void claim() {
        AgentBridge.install(request -> Map.of("status", "ok"));
        AgentBridge.claim(
                Map.of("application", "advice-test", "mode", "dev", "sensors", List.of("executors")), capture, reopen);
    }
}
