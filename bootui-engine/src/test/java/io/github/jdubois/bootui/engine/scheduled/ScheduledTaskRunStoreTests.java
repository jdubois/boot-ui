package io.github.jdubois.bootui.engine.scheduled;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScheduledTaskRunStoreTests {

    @Test
    void recordsASuccessfulRun() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);

        store.record("com.example.Job.run", 1_000L, 25L, true, null, null, "scheduling-1");

        List<ScheduledTaskRunStore.Run> runs = store.runs();
        assertThat(runs).hasSize(1);
        ScheduledTaskRunStore.Run run = runs.get(0);
        assertThat(run.runnable()).isEqualTo("com.example.Job.run");
        assertThat(run.startTimestamp()).isEqualTo(1_000L);
        assertThat(run.durationMs()).isEqualTo(25L);
        assertThat(run.success()).isTrue();
        assertThat(run.exceptionClassName()).isNull();
        assertThat(run.message()).isNull();
        assertThat(run.thread()).isEqualTo("scheduling-1");
        assertThat(run.sequence()).isPositive();
    }

    @Test
    void recordsAFailedRunWithExceptionDetails() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);

        store.record(
                "com.example.Job.run", 1_000L, 5L, false, "java.lang.IllegalStateException", "boom", "scheduling-1");

        ScheduledTaskRunStore.Run run = store.runs().get(0);
        assertThat(run.success()).isFalse();
        assertThat(run.exceptionClassName()).isEqualTo("java.lang.IllegalStateException");
        assertThat(run.message()).isEqualTo("boom");
    }

    @Test
    void returnsRunsNewestFirst() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);

        store.record("job.a", 1_000L, 1L, true, null, null, null);
        store.record("job.b", 2_000L, 1L, true, null, null, null);
        store.record("job.c", 3_000L, 1L, true, null, null, null);

        List<ScheduledTaskRunStore.Run> runs = store.runs();
        assertThat(runs).extracting(ScheduledTaskRunStore.Run::runnable).containsExactly("job.c", "job.b", "job.a");
    }

    @Test
    void evictsOldestRunsOnceBoundIsExceeded() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(2);

        store.record("job.a", 1_000L, 1L, true, null, null, null);
        store.record("job.b", 2_000L, 1L, true, null, null, null);
        store.record("job.c", 3_000L, 1L, true, null, null, null);

        List<ScheduledTaskRunStore.Run> runs = store.runs();
        assertThat(runs).hasSize(2);
        assertThat(runs).extracting(ScheduledTaskRunStore.Run::runnable).containsExactly("job.c", "job.b");
    }

    @Test
    void clampsNegativeDurationsToZero() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);

        store.record("job.a", 1_000L, -50L, true, null, null, null);

        assertThat(store.runs().get(0).durationMs()).isZero();
    }

    @Test
    void treatsMaxEntriesBelowOneAsOne() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(0);

        store.record("job.a", 1_000L, 1L, true, null, null, null);
        store.record("job.b", 2_000L, 1L, true, null, null, null);

        assertThat(store.runs()).hasSize(1);
    }

    @Test
    void clearRemovesAllRuns() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        store.record("job.a", 1_000L, 1L, true, null, null, null);

        store.clear();

        assertThat(store.runs()).isEmpty();
    }

    @Test
    void notifiesSubscribersOnRecordAndClear() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        AtomicInteger notifications = new AtomicInteger();
        store.subscribe(notifications::incrementAndGet);

        store.record("job.a", 1_000L, 1L, true, null, null, null);
        store.clear();

        assertThat(notifications.get()).isEqualTo(2);
    }

    @Test
    void unsubscribeStopsFurtherNotifications() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        AtomicInteger notifications = new AtomicInteger();
        Runnable unsubscribe = store.subscribe(notifications::incrementAndGet);

        unsubscribe.run();
        store.record("job.a", 1_000L, 1L, true, null, null, null);

        assertThat(notifications.get()).isZero();
    }

    @Test
    void aMisbehavingListenerDoesNotPreventCapture() {
        ScheduledTaskRunStore store = new ScheduledTaskRunStore(10);
        store.subscribe(() -> {
            throw new RuntimeException("boom");
        });

        store.record("job.a", 1_000L, 1L, true, null, null, null);

        assertThat(store.runs()).hasSize(1);
    }
}
