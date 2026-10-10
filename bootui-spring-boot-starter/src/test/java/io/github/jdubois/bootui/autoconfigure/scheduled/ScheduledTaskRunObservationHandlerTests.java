package io.github.jdubois.bootui.autoconfigure.scheduled;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.sample.SampleScheduledTask;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void eachRunIsCurrentAsItsOwnExecutionWhileItRunsAndIsRecordedWithIt() throws Exception {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ScheduledTaskRunObservationHandler(store, selfDataFilter));
        CorrelationContext request = CorrelationContext.forRequest("0123456789abcdef");
        List<CorrelationContext> seen = new ArrayList<>();

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(request)) {
            for (int i = 0; i < 2; i++) {
                ScheduledTaskObservationContext context = applicationOwnedContext();
                Observation.createNotStarted("tasks.scheduled.execution", () -> context, registry)
                        .observe(() -> seen.add(BootUiCorrelation.current()));
            }
            assertThat(BootUiCorrelation.current())
                    .as("the scope open before the run is restored after it")
                    .isEqualTo(request);
        }

        assertThat(seen).extracting(CorrelationContext::requestId).containsOnlyNulls();
        assertThat(seen)
                .extracting(CorrelationContext::executionId)
                .allSatisfy(id -> assertThat(id).matches("[0-9a-f]{16}"))
                .doesNotHaveDuplicates();
        assertThat(store.runs())
                .extracting(ScheduledTaskRunStore.Run::executionId)
                .containsExactlyInAnyOrderElementsOf(
                        seen.stream().map(CorrelationContext::executionId).toList());
    }

    @Test
    void nestedScopesRestoreTheAmbientRequestAfterTheScheduledRun() throws Exception {
        assertNestedScopesRestore(CorrelationContext.forRequest("0123456789abcdef"));
    }

    @Test
    void nestedScopesLeaveAnInitiallyUnownedWorkerUnowned() throws Exception {
        assertNestedScopesRestore(CorrelationContext.NONE);
    }

    @Test
    void sharedObservationScopesRestoreEachWorkersOwnAmbientContext() throws Exception {
        ScheduledTaskRunObservationHandler handler =
                new ScheduledTaskRunObservationHandler(new ScheduledTaskRunStore(10), selfDataFilter);
        ScheduledTaskObservationContext context = applicationOwnedContext();
        handler.onStart(context);
        CountDownLatch bothOpen = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (String request : List.of("0123456789abcdef", "fedcba9876543210")) {
            Thread worker = new Thread(() -> {
                CorrelationContext ambient = CorrelationContext.forRequest(request);
                try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(ambient)) {
                    handler.onScopeOpened(context);
                    try {
                        bothOpen.countDown();
                        assertThat(bothOpen.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(BootUiCorrelation.current().executionId()).isNotNull();
                    } finally {
                        handler.onScopeClosed(context);
                    }
                    assertThat(BootUiCorrelation.current()).isEqualTo(ambient);
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(6000);
            assertThat(worker.isAlive()).isFalse();
        }
        assertThat(failure.get()).isNull();
    }

    private void assertNestedScopesRestore(CorrelationContext ambient) throws Exception {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ScheduledTaskRunObservationHandler(store, selfDataFilter));
        ScheduledTaskObservationContext context = applicationOwnedContext();
        Observation observation = Observation.createNotStarted("tasks.scheduled.execution", () -> context, registry);

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(ambient)) {
            observation.observe(() -> {
                CorrelationContext scheduled = BootUiCorrelation.current();
                assertThat(scheduled.executionId()).isNotNull();
                try (Observation.Scope nested = observation.openScope()) {
                    assertThat(BootUiCorrelation.current()).isEqualTo(scheduled);
                }
                assertThat(BootUiCorrelation.current()).isEqualTo(scheduled);
            });
            assertThat(BootUiCorrelation.current()).isEqualTo(ambient);
        } finally {
            BootUiCorrelation.replace(CorrelationContext.NONE);
        }
    }

    private ScheduledTaskObservationContext applicationOwnedContext() throws Exception {
        SampleScheduledTask task = new SampleScheduledTask();
        Method method = SampleScheduledTask.class.getDeclaredMethod("run");
        return new ScheduledTaskObservationContext(task, method);
    }
}
