package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** The thread-activity sensor's tracker (PLAN-v2 §5.16, M5-5e): reports, bounds, and never keeps what it tracks. */
class ThreadTrackerTests {

    private static final long GENERATION = 7L;
    private static final long REQUEST = 0x42L;

    private final ThreadTracker tracker = new ThreadTracker();
    private final List<ThreadTracker.Entry> reports = new ArrayList<>();

    private boolean track(Object referent, boolean thread, long request) {
        return tracker.track(
                referent, thread, GENERATION, request, 0L, 0, 1, 0, 3, 5L, 9L, 0L, System.currentTimeMillis(), reports);
    }

    @Test
    void aThreadStillAliveWhenItsRequestEndsIsReportedAndAJoinedOneIsNot() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread running = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        Thread joined = new Thread(() -> {});
        running.start();
        joined.start();
        joined.join();
        try {
            assertThat(track(running, true, REQUEST)).isTrue();
            assertThat(track(joined, true, REQUEST)).isTrue();
            assertThat(tracker.anyWaiting()).isTrue();

            tracker.requestEnded(GENERATION, REQUEST, reports);

            assertThat(reports).singleElement().satisfies(report -> {
                assertThat(report.reported).isEqualTo(ThreadTracker.THREAD_LEFT_RUNNING);
                assertThat(report.request).isEqualTo(REQUEST);
                assertThat(report.target).isEqualTo(3);
                assertThat(report.stamp).isEqualTo(5L);
                assertThat(report.frames).isEqualTo(9L);
                assertThat(report.get()).as("a report never holds the thread").isNull();
            });
            assertThat(tracker.size()).as("threads are forgotten at their request's end").isZero();
            assertThat(tracker.anyWaiting()).isFalse();
        } finally {
            release.countDown();
            running.join();
        }
    }

    @Test
    void anExecutorNotShutDownWhenItsRequestEndsIsReportedThenItsShutdownLandsOnItsCreation() {
        ExecutorService leaked = Executors.newFixedThreadPool(1);
        ExecutorService closed = Executors.newFixedThreadPool(1);
        try {
            track(leaked, false, REQUEST);
            track(closed, false, REQUEST);
            tracker.shutdown(closed, false, GENERATION, reports);
            assertThat(reports).singleElement().satisfies(report -> assertThat(report.reported)
                    .isEqualTo(ThreadTracker.EXECUTOR_SHUT_DOWN));
            reports.clear();

            tracker.requestEnded(GENERATION, REQUEST, reports);

            assertThat(reports).singleElement().satisfies(report -> assertThat(report.reported)
                    .isEqualTo(ThreadTracker.EXECUTOR_LEFT_RUNNING));
            reports.clear();
            tracker.shutdown(leaked, false, GENERATION, reports);
            tracker.shutdown(leaked, false, GENERATION, reports);
            assertThat(reports)
                    .as("its first shutdown only")
                    .singleElement()
                    .satisfies(report -> assertThat(report.reported).isEqualTo(ThreadTracker.EXECUTOR_SHUT_DOWN));
            assertThat(tracker.size()).isZero();
        } finally {
            leaked.shutdownNow();
            closed.shutdownNow();
        }
    }

    @Test
    void aShutdownByACleanerIsAReclaimAndAnUntrackedExecutorReportsNothing() {
        Object executor = new Object();
        track(executor, false, 0L);
        tracker.shutdown(new Object(), false, GENERATION, reports);
        assertThat(reports).isEmpty();

        tracker.shutdown(executor, true, GENERATION, reports);

        assertThat(reports).singleElement().satisfies(report -> assertThat(report.reported)
                .isEqualTo(ThreadTracker.EXECUTOR_RECLAIMED));
    }

    @Test
    void anUnownedThreadOrOneStartedAfterItsRequestEndedIsNotTracked() throws Exception {
        Thread thread = new Thread(() -> {});
        assertThat(track(thread, true, 0L)).isFalse();
        tracker.requestEnded(GENERATION, REQUEST, reports);
        assertThat(track(thread, true, REQUEST))
                .as("its request already ended")
                .isFalse();
        assertThat(tracker.afterEnd.sum()).isEqualTo(1L);
        assertThat(reports).isEmpty();
    }

    @Test
    void anotherGenerationForgetsEverythingAndItsEndsAreIgnored() {
        Object executor = new Object();
        track(executor, false, REQUEST);
        tracker.requestEnded(GENERATION + 1, REQUEST, reports);
        assertThat(reports).isEmpty();
        assertThat(tracker.size()).isOne();

        tracker.track(executor, false, GENERATION + 1, 0L, 0L, 0, 1, 0, 3, 0L, 0L, 0L, 0L, reports);

        assertThat(tracker.size()).as("only the new generation's entry").isOne();
        assertThat(tracker.anyWaiting()).isFalse();
    }

    @Test
    void aFullTableLeavesNewEntriesUntrackedCounted() {
        List<Object> kept = new ArrayList<>();
        for (int i = 0; i < ThreadTracker.MAX_ENTRIES; i++) {
            Object executor = new Object();
            kept.add(executor);
            assertThat(track(executor, false, 0L)).isTrue();
        }
        assertThat(track(new Object(), false, 0L)).isFalse();
        assertThat(tracker.untracked.sum()).isEqualTo(1L);
        assertThat(kept).hasSize(ThreadTracker.MAX_ENTRIES);
    }

    @Test
    void entriesWaitingLongerThanTheirBoundStopWaitingCounted() {
        Object executor = new Object();
        tracker.track(executor, false, GENERATION, REQUEST, 0L, 0, 1, 0, 3, 0L, 0L, 0L, 0L, reports);
        assertThat(tracker.anyWaiting()).isTrue();

        tracker.track(new Object(), false, GENERATION, 0L, 0L, 0, 1, 0, 3, 0L, 0L, 0L, ThreadTracker.WAIT_MILLIS, reports);

        assertThat(tracker.anyWaiting()).isFalse();
        assertThat(tracker.unresolved.sum()).isEqualTo(1L);
        tracker.requestEnded(GENERATION, REQUEST, reports);
        assertThat(reports).isEmpty();
    }

    /** Nothing the tracker holds keeps a thread or an executor alive: a collected executor is reported reclaimed. */
    @Test
    void neverKeepsWhatItTracksAndReportsACollectedExecutorAsReclaimed() throws Exception {
        WeakReference<Object> executor = trackGarbage();
        for (int i = 0; i < 50 && executor.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(executor.get()).as("collected").isNull();
        for (int i = 0; i < 50 && reports.isEmpty(); i++) {
            tracker.expunge(reports);
            Thread.sleep(20);
        }
        assertThat(reports).singleElement().satisfies(report -> assertThat(report.reported)
                .isEqualTo(ThreadTracker.EXECUTOR_RECLAIMED));
        assertThat(tracker.size()).isZero();
        assertThat(tracker.anyWaiting()).isFalse();
    }

    private WeakReference<Object> trackGarbage() {
        Object executor = new Object();
        track(executor, false, REQUEST);
        return new WeakReference<>(executor);
    }
}
