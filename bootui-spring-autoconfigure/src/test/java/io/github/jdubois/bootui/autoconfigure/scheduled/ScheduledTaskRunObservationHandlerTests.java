package io.github.jdubois.bootui.autoconfigure.scheduled;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.sample.SampleScheduledTask;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

class ScheduledTaskRunObservationHandlerTests {

    private final BootUiSelfDataFilter selfDataFilter = new BootUiSelfDataFilter(new BootUiProperties());

    @Test
    void capturesASuccessfulExecution() throws Exception {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        ScheduledTaskRunObservationHandler handler = new ScheduledTaskRunObservationHandler(store, selfDataFilter);
        ScheduledTaskObservationContext context = applicationOwnedContext();

        handler.onStart(context);
        context.setComplete(true);
        handler.onStop(context);

        List<ScheduledTaskRunStore.Run> runs = store.runs();
        assertThat(runs).hasSize(1);
        ScheduledTaskRunStore.Run run = runs.get(0);
        assertThat(run.runnable()).isEqualTo(SampleScheduledTask.class.getName() + ".run");
        assertThat(run.success()).isTrue();
        assertThat(run.exceptionClassName()).isNull();
        assertThat(run.thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(run.durationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void capturesAFailedExecutionWithTheThrownException() throws Exception {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        ScheduledTaskRunObservationHandler handler = new ScheduledTaskRunObservationHandler(store, selfDataFilter);
        ScheduledTaskObservationContext context = applicationOwnedContext();

        handler.onStart(context);
        context.setError(new IllegalStateException("boom"));
        handler.onStop(context);

        ScheduledTaskRunStore.Run run = store.runs().get(0);
        assertThat(run.success()).isFalse();
        assertThat(run.exceptionClassName()).isEqualTo("java.lang.IllegalStateException");
        assertThat(run.message()).isEqualTo("boom");
    }

    @Test
    void supportsOnlyScheduledTaskObservationContexts() throws Exception {
        ScheduledTaskRunObservationHandler handler =
                new ScheduledTaskRunObservationHandler(new ScheduledTaskRunStore(10), selfDataFilter);

        assertThat(handler.supportsContext(applicationOwnedContext())).isTrue();
        assertThat(handler.supportsContext(new io.micrometer.observation.Observation.Context()))
                .isFalse();
    }

    @Test
    void skipsCapturingBootUisOwnScheduledTasks() throws Exception {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        ScheduledTaskRunObservationHandler handler = new ScheduledTaskRunObservationHandler(store, selfDataFilter);
        Method method = BootUiSelfDataFilter.class.getDeclaredMethod("shouldIncludeScheduledTask", String.class);
        ScheduledTaskObservationContext context = new ScheduledTaskObservationContext(selfDataFilter, method);

        handler.onStart(context);
        context.setComplete(true);
        handler.onStop(context);

        assertThat(store.runs()).isEmpty();
    }

    private ScheduledTaskObservationContext applicationOwnedContext() throws Exception {
        SampleScheduledTask task = new SampleScheduledTask();
        Method method = SampleScheduledTask.class.getDeclaredMethod("run");
        return new ScheduledTaskObservationContext(task, method);
    }
}
