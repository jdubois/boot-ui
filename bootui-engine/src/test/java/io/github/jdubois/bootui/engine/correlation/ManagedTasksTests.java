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
    void aTaskSubmittedOutsideAnyRequestIsUnchangedAndBootUiWorkStaysBootUis() {
        Runnable plain = () -> {};

        assertThat(ManagedTasks.propagate(plain)).isSameAs(plain);
        assertThat(ManagedTasks.taskContext(CorrelationContext.BOOTUI)).isEqualTo(CorrelationContext.BOOTUI);
        assertThat(ManagedTasks.taskContext(CorrelationContext.forExecution("e1")))
                .as("a scheduled run's own task keeps its execution")
                .isEqualTo(CorrelationContext.forExecution("e1"));
        assertThat(ManagedTasks.taskContext(null)).isNull();
    }
}
