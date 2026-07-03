package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ActivityCapturePollerTests {

    /** Records every entry ever appended to it, in append order, ignoring query/prune/close. */
    private static final class RecordingStore implements ActivityStore {
        final List<StoredActivityEntry> allAppended = new ArrayList<>();

        @Override
        public void appendBatch(List<StoredActivityEntry> entries) {
            allAppended.addAll(entries);
        }

        @Override
        public ActivityPage query(ActivityQuery query) {
            return ActivityPage.EMPTY;
        }
    }

    /** A store whose appendBatch throws on its first invocation only, to simulate a transient failure. */
    private static final class FlakyStore implements ActivityStore {
        final AtomicInteger callCount = new AtomicInteger();
        final List<StoredActivityEntry> allAppended = new ArrayList<>();

        @Override
        public void appendBatch(List<StoredActivityEntry> entries) {
            if (callCount.incrementAndGet() == 1) {
                throw new ActivityStoreException("simulated failure", null);
            }
            allAppended.addAll(entries);
        }

        @Override
        public ActivityPage query(ActivityQuery query) {
            return ActivityPage.EMPTY;
        }
    }

    @Test
    void startPollsOnScheduleAndCapturesNewEntries() throws InterruptedException {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);
        try (ActivityCapturePoller poller =
                new ActivityCapturePoller(coordinator, () -> List.of(entry("1", "REQUEST", 1, "OK", "a")))) {
            poller.start(Duration.ofMillis(10));
            waitUntil(() -> !store.allAppended.isEmpty(), Duration.ofSeconds(2));
            assertThat(store.allAppended).extracting(e -> e.entry().id()).contains("1");
        }
    }

    @Test
    void captureNowRunsASingleTickSynchronouslyWithoutStarting() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);
        try (ActivityCapturePoller poller =
                new ActivityCapturePoller(coordinator, () -> List.of(entry("1", "REQUEST", 1, "OK", "a")))) {
            poller.captureNow();
            assertThat(store.allAppended).extracting(e -> e.entry().id()).containsExactly("1");
        }
    }

    @Test
    void startIsIdempotentASecondCallIsIgnored() throws InterruptedException {
        AtomicInteger feedCalls = new AtomicInteger();
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);
        try (ActivityCapturePoller poller = new ActivityCapturePoller(coordinator, () -> {
            feedCalls.incrementAndGet();
            return List.of();
        })) {
            poller.start(Duration.ofMillis(10));
            waitUntil(() -> feedCalls.get() > 0, Duration.ofSeconds(2));
            // A second start() with a different interval must not add a second schedule; if it had,
            // the tick rate (and thus feedCalls) would climb far faster than a single 10ms schedule.
            poller.start(Duration.ofMillis(5000));
            int countAfterSecondStart = feedCalls.get();
            Thread.sleep(100);
            int countLater = feedCalls.get();
            // Still ticking on the original 10ms schedule, not stalled and not doubled.
            assertThat(countLater).isGreaterThan(countAfterSecondStart);
        }
    }

    @Test
    void closeStopsFuturePolling() throws InterruptedException {
        AtomicInteger feedCalls = new AtomicInteger();
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);
        ActivityCapturePoller poller = new ActivityCapturePoller(coordinator, () -> {
            feedCalls.incrementAndGet();
            return List.of();
        });
        poller.start(Duration.ofMillis(10));
        waitUntil(() -> feedCalls.get() > 0, Duration.ofSeconds(2));
        poller.close();
        int countAtClose = feedCalls.get();
        Thread.sleep(100); // well past several would-be poll cycles
        assertThat(feedCalls.get()).isEqualTo(countAtClose);
    }

    @Test
    void closeCapturesOutstandingEntriesEvenIfPollerWasNeverStarted() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);
        ActivityCapturePoller poller =
                new ActivityCapturePoller(coordinator, () -> List.of(entry("1", "REQUEST", 1, "OK", "a")));
        // start() was never called, so without a final capture on close() this entry would never be
        // captured at all.
        poller.close();
        assertThat(store.allAppended).extracting(e -> e.entry().id()).containsExactly("1");
    }

    @Test
    void closeCapturesEntriesEvenWhenTheNextScheduledTickIsFarAway() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);
        ActivityCapturePoller poller =
                new ActivityCapturePoller(coordinator, () -> List.of(entry("1", "REQUEST", 1, "OK", "a")));
        // The next (first) scheduled tick is 60s away; close() must not wait for it, or "1" would be
        // silently dropped on every shutdown that happens to land between two ticks.
        poller.start(Duration.ofSeconds(60));
        poller.close();
        assertThat(store.allAppended).extracting(e -> e.entry().id()).containsExactly("1");
    }

    @Test
    void aFeedSupplierThatThrowsDoesNotStopFuturePolling() throws InterruptedException {
        AtomicInteger feedCalls = new AtomicInteger();
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);
        try (ActivityCapturePoller poller = new ActivityCapturePoller(coordinator, () -> {
            if (feedCalls.incrementAndGet() == 1) {
                throw new RuntimeException("simulated feed failure");
            }
            return List.of(entry("1", "REQUEST", 1, "OK", "a"));
        })) {
            poller.start(Duration.ofMillis(10));
            waitUntil(() -> !store.allAppended.isEmpty(), Duration.ofSeconds(2));
            assertThat(store.allAppended).extracting(e -> e.entry().id()).contains("1");
        }
    }

    @Test
    void aStoreThatThrowsDoesNotStopFuturePolling() throws InterruptedException {
        FlakyStore store = new FlakyStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);
        List<ActivityEntryDto> feed = new ArrayList<>();
        feed.add(entry("1", "REQUEST", 1, "OK", "a"));
        try (ActivityCapturePoller poller = new ActivityCapturePoller(coordinator, () -> feed)) {
            poller.start(Duration.ofMillis(10));
            // Wait for the first tick to actually fail before introducing "2", so "1" is guaranteed to
            // be marked seen (and lost, since the store threw) on its own, isolated tick.
            waitUntil(() -> store.callCount.get() >= 1, Duration.ofSeconds(2));
            // The coordinator's seen-set already marked "1" as seen (see
            // ActivityCaptureCoordinator.ingest's ordering), so once the store recovers only genuinely
            // new entries appear.
            feed.add(entry("2", "REQUEST", 2, "OK", "b"));
            waitUntil(() -> !store.allAppended.isEmpty(), Duration.ofSeconds(2));
            assertThat(store.allAppended).extracting(e -> e.entry().id()).contains("2");
        }
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return;
            }
            Thread.sleep(10);
        }
    }
}
