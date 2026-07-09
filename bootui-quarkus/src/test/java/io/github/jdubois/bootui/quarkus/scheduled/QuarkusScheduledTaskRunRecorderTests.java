package io.github.jdubois.bootui.quarkus.scheduled;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.quarkus.scheduler.FailedExecution;
import io.quarkus.scheduler.ScheduledExecution;
import io.quarkus.scheduler.SuccessfulExecution;
import io.quarkus.scheduler.Trigger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link QuarkusScheduledTaskRunRecorder} feeds the shared engine {@link ScheduledTaskRunStore}
 * from the CDI {@link SuccessfulExecution}/{@link FailedExecution} events the same way Spring's
 * {@code ScheduledTaskRunObservationHandler} feeds it from Micrometer's own {@code
 * ScheduledTaskObservationContext} — see {@code docs/PLAN.md} §3.4.
 */
class QuarkusScheduledTaskRunRecorderTests {

    @Test
    void recordsASuccessfulExecutionUsingTheTriggerFireTimeAsTheStartTimestamp() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        QuarkusScheduledTaskRunRecorder recorder = new QuarkusScheduledTaskRunRecorder(store);
        Instant fireTime = Instant.ofEpochMilli(1_000L);

        recorder.onSuccess(new SuccessfulExecution(execution(new FakeTrigger("com.example.Job#run"), fireTime)));

        List<ScheduledTaskRunStore.Run> runs = store.runs();
        assertThat(runs).hasSize(1);
        ScheduledTaskRunStore.Run run = runs.get(0);
        assertThat(run.runnable()).isEqualTo("com.example.Job#run");
        assertThat(run.startTimestamp()).isEqualTo(1_000L);
        assertThat(run.success()).isTrue();
        assertThat(run.exceptionClassName()).isNull();
    }

    @Test
    void recordsAFailedExecutionWithTheThrownExceptionDetail() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        QuarkusScheduledTaskRunRecorder recorder = new QuarkusScheduledTaskRunRecorder(store);
        RuntimeException failure = new RuntimeException("boom");

        recorder.onFailure(new FailedExecution(
                execution(new FakeTrigger("com.example.Job#fail"), Instant.ofEpochMilli(2_000L)), failure));

        ScheduledTaskRunStore.Run run = store.runs().get(0);
        assertThat(run.success()).isFalse();
        assertThat(run.exceptionClassName()).isEqualTo("java.lang.RuntimeException");
        assertThat(run.message()).isEqualTo("boom");
    }

    @Test
    void skipsAProgrammaticallyRegisteredJobWithNoMethodDescription() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        QuarkusScheduledTaskRunRecorder recorder = new QuarkusScheduledTaskRunRecorder(store);

        recorder.onSuccess(new SuccessfulExecution(execution(new FakeTrigger(null), Instant.now())));

        assertThat(store.runs()).isEmpty();
    }

    @Test
    void filtersOutBootUisOwnInternalScheduledMethods() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        QuarkusScheduledTaskRunRecorder recorder = new QuarkusScheduledTaskRunRecorder(store);

        recorder.onSuccess(new SuccessfulExecution(
                execution(new FakeTrigger("io.github.jdubois.bootui.quarkus.Internal#tick"), Instant.now())));

        assertThat(store.runs()).isEmpty();
    }

    private static ScheduledExecution execution(Trigger trigger, Instant fireTime) {
        return new ScheduledExecution() {
            @Override
            public Trigger getTrigger() {
                return trigger;
            }

            @Override
            public Instant getFireTime() {
                return fireTime;
            }

            @Override
            public Instant getScheduledFireTime() {
                return fireTime;
            }
        };
    }

    private record FakeTrigger(String methodDescription) implements Trigger {
        @Override
        public String getId() {
            return "test-trigger";
        }

        @Override
        public Instant getNextFireTime() {
            return null;
        }

        @Override
        public Instant getPreviousFireTime() {
            return null;
        }

        @Override
        public boolean isOverdue() {
            return false;
        }

        @Override
        public String getMethodDescription() {
            return methodDescription;
        }
    }
}
