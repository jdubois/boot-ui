package io.github.jdubois.bootui.engine.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ManagedTasksTests {

    @Test
    void aTaskRunsAsAnExecutionOfTheRequestThatSubmittedItAndRestoresItsThread() throws Exception {
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        Runnable task;
        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forRequest("r1").withTrace("t1", "s1"))) {
            task = ManagedTasks.propagate(() -> seen.set(BootUiCorrelation.current()));
        }

        Thread worker = new Thread(task);
        worker.start();
        worker.join();

        assertThat(seen.get().requestId()).isEqualTo("r1");
        assertThat(seen.get().traceId()).isEqualTo("t1");
        assertThat(seen.get().executionId()).startsWith("task-");
        task.run();
        assertThat(BootUiCorrelation.current()).isEqualTo(CorrelationContext.NONE);
    }

    @Test
    void aTaskRunOnAThreadAlreadyWorkingForItsRequestKeepsThatContext() {
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        CorrelationContext request = CorrelationContext.forRequest("r1").withExecutionId("e0");
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(request)) {
            Runnable task = ManagedTasks.propagate(() -> seen.set(BootUiCorrelation.current()));
            task.run();
            assertThat(seen.get()).as("caller-runs: no empty nested execution").isEqualTo(request);
            assertThat(ManagedTasks.open(ManagedTasks.taskContext(request))).isNull();
            assertThat(BootUiCorrelation.current()).isEqualTo(request);
        }
        assertThat(BootUiCorrelation.current()).isEqualTo(CorrelationContext.NONE);
    }

    @Test
    void aTaskSubmittedOutsideAnyRequestIsUnchangedAndBootUiWorkStaysBootUis() {
        Runnable plain = () -> {};

        assertThat(ManagedTasks.propagate(plain)).isSameAs(plain);
        assertThat(ManagedTasks.taskContext(CorrelationContext.BOOTUI)).isEqualTo(CorrelationContext.BOOTUI);
        assertThat(ManagedTasks.taskContext(CorrelationContext.forExecution("e1")))
                .as("a scheduled run's own task keeps its execution")
                .isEqualTo(CorrelationContext.forExecution("e1"));
        assertThat(ManagedTasks.taskContext(null)).isNull();
    }

    @Test
    void aPeriodicTaskBelongsToItsRequestOnItsFirstRunOnly() {
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        Runnable task;
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            task = ManagedTasks.propagate(() -> seen.set(BootUiCorrelation.current()));
        }

        task.run();
        assertThat(seen.get().requestId()).isEqualTo("r1");
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r2"))) {
            task.run();
            assertThat(seen.get().requestId())
                    .as("a scheduler runs a periodic task again and again; later runs belong to no request")
                    .isNull();
            assertThat(BootUiCorrelation.current().requestId()).isEqualTo("r2");
        }
    }

    @Test
    void aTaskOnAnotherThreadIsItsOwnExecutionEvenWhereADecoratorAlreadyRestoredItsRequest() throws Exception {
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        CorrelationContext request = CorrelationContext.forRequest("r1").withExecutionId("e0");
        Runnable task;
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(request)) {
            task = ManagedTasks.propagate(() -> seen.set(BootUiCorrelation.current()));
        }
        // An application decorator composed around BootUI's, such as Micrometer's, restores the request first.
        Thread worker = new Thread(() -> {
            try (BootUiCorrelation.Scope restored = BootUiCorrelation.open(request)) {
                task.run();
            }
        });
        worker.start();
        worker.join();

        assertThat(seen.get().requestId()).isEqualTo("r1");
        assertThat(seen.get().executionId()).startsWith("task-");
    }

    @Test
    void aTaskIsPropagatedOnceHoweverManyDecoratorsCarryBootUis() {
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            Runnable once = ManagedTasks.propagate(() -> {});
            assertThat(ManagedTasks.propagate(once)).isSameAs(once);
        }
    }

    @Test
    void aScheduledFutureDecoratedWhileAPropagatedTaskRunsIsATriggerReschedulingAndBelongsToNoRequest()
            throws Exception {
        java.util.concurrent.ScheduledThreadPoolExecutor scheduler =
                new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        try {
            java.util.concurrent.RunnableScheduledFuture<?> next = (java.util.concurrent.RunnableScheduledFuture<?>)
                    scheduler.schedule(() -> {}, 1, java.util.concurrent.TimeUnit.HOURS);
            AtomicReference<Runnable> rescheduled = new AtomicReference<>();
            Runnable task;
            try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
                // What Spring's ReschedulingRunnable does at the end of each run: decorate its next future.
                task = ManagedTasks.propagate(() -> rescheduled.set(ManagedTasks.propagate(next)));
                assertThat(ManagedTasks.propagate(next))
                        .as("scheduled from the request itself, the first run is the request's")
                        .isNotSameAs(next);
            }
            Thread worker = new Thread(task);
            worker.start();
            worker.join();

            assertThat(rescheduled.get())
                    .as("the next cron run belongs to no request")
                    .isSameAs(next);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void aPlainTaskSubmittedFromAPropagatedTaskStillBelongsToTheRequest() throws Exception {
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        AtomicReference<Runnable> inner = new AtomicReference<>();
        Runnable outer;
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            outer = ManagedTasks.propagate(
                    () -> inner.set(ManagedTasks.propagate(() -> seen.set(BootUiCorrelation.current()))));
        }
        Thread first = new Thread(outer);
        first.start();
        first.join();
        Thread second = new Thread(inner.get());
        second.start();
        second.join();

        assertThat(seen.get().requestId())
                .as("a nested @Async call keeps its request")
                .isEqualTo("r1");
    }
}
