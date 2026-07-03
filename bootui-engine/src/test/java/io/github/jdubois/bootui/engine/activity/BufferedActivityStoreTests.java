package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BufferedActivityStoreTests {

    private static final String INSTANCE = "app-1";
    private static final Duration LONG_INTERVAL = Duration.ofSeconds(60);

    /**
     * A controllable fake for the durable tier: can be told to fail its next N {@link #appendBatch}
     * calls (to simulate an outage), records what it actually accepted, and tracks {@link #prune} /
     * {@link #close} invocations.
     */
    private static final class FakeDurableStore implements ActivityStore {
        final List<StoredActivityEntry> accepted = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();
        final AtomicInteger pruneCalls = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void appendBatch(List<StoredActivityEntry> entries) {
            if (failuresRemaining.get() > 0) {
                failuresRemaining.decrementAndGet();
                throw new RuntimeException("simulated durable-storage outage");
            }
            accepted.addAll(entries);
        }

        @Override
        public ActivityPage query(ActivityQuery query) {
            List<StoredActivityEntry> sorted = new ArrayList<>(accepted);
            sorted.sort(Comparator.comparingLong(StoredActivityEntry::seq).reversed());
            boolean hasMore = sorted.size() > query.pageSize();
            List<StoredActivityEntry> page = hasMore ? sorted.subList(0, query.pageSize()) : sorted;
            return new ActivityPage(page, null, hasMore);
        }

        @Override
        public void prune(String instanceId, long olderThanEpochMillis) {
            pruneCalls.incrementAndGet();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private StoredActivityEntry stampedEntry(long seq, String id) {
        return new StoredActivityEntry(INSTANCE, seq, entry(id, "REQUEST", seq, "OK", "entry " + id));
    }

    @Test
    void appendedEntriesAreVisibleImmediatelyBeforeAnyFlush() {
        FakeDurableStore durable = new FakeDurableStore();
        try (BufferedActivityStore store =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, LONG_INTERVAL, 100)) {
            store.append(stampedEntry(1, "1"));

            assertThat(store.pendingCount()).isEqualTo(1);
            assertThat(durable.accepted).isEmpty();
            assertThat(store.query(ActivityQuery.firstPage(INSTANCE)).entryDtos())
                    .extracting(ActivityEntryDto::id)
                    .containsExactly("1");
        }
    }

    @Test
    void flushNowDrainsPendingIntoDurableStoreAndClearsPendingCount() {
        FakeDurableStore durable = new FakeDurableStore();
        try (BufferedActivityStore store =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, LONG_INTERVAL, 100)) {
            store.append(stampedEntry(1, "1"));
            store.flushNow();

            assertThat(store.pendingCount()).isZero();
            assertThat(durable.accepted).extracting(e -> e.entry().id()).containsExactly("1");
            // Still visible after flush: the hot cache is a pure cache, not cleared once durably written.
            assertThat(store.query(ActivityQuery.firstPage(INSTANCE)).entryDtos())
                    .extracting(ActivityEntryDto::id)
                    .containsExactly("1");
        }
    }

    @Test
    void mergeForReadsDedupesEntriesPresentInBothTiersAfterFlush() {
        FakeDurableStore durable = new FakeDurableStore();
        try (BufferedActivityStore store =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, LONG_INTERVAL, 100)) {
            store.append(stampedEntry(1, "1"));
            store.append(stampedEntry(2, "2"));
            store.append(stampedEntry(3, "3"));
            store.flushNow();

            ActivityPage page = store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, null, 3));
            assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("3", "2", "1");
            assertThat(page.hasMore()).isFalse();
        }
    }

    @Test
    void requeuesFailedBatchAndSucceedsOnNextFlush() {
        FakeDurableStore durable = new FakeDurableStore();
        durable.failuresRemaining.set(1);
        try (BufferedActivityStore store =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, LONG_INTERVAL, 100)) {
            store.append(stampedEntry(1, "1"));

            store.flushNow(); // fails, re-queues
            assertThat(store.pendingCount()).isEqualTo(1);
            assertThat(durable.accepted).isEmpty();

            store.flushNow(); // durable is healthy again now
            assertThat(store.pendingCount()).isZero();
            assertThat(durable.accepted).extracting(e -> e.entry().id()).containsExactly("1");
        }
    }

    @Test
    void requeuedEntriesPreserveOrderAheadOfNewerCaptures() {
        FakeDurableStore durable = new FakeDurableStore();
        durable.failuresRemaining.set(1);
        try (BufferedActivityStore store =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, LONG_INTERVAL, 100)) {
            store.append(stampedEntry(1, "1"));
            store.flushNow(); // fails; "1" is re-queued
            store.append(stampedEntry(2, "2")); // captured while the outage was in effect

            store.flushNow(); // succeeds now
            assertThat(durable.accepted).extracting(e -> e.entry().id()).containsExactly("1", "2");
        }
    }

    @Test
    void tripsPendingQueueDuringProlongedOutageButKeepsHotCacheIntact() {
        FakeDurableStore durable = new FakeDurableStore();
        durable.failuresRemaining.set(Integer.MAX_VALUE);
        try (BufferedActivityStore store =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, LONG_INTERVAL, 3)) {
            for (long i = 1; i <= 5; i++) {
                store.append(stampedEntry(i, String.valueOf(i)));
            }

            assertThat(store.pendingCount()).isEqualTo(3);
            // All 5 remain visible from the hot cache regardless of the pending-queue cap.
            assertThat(store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, null, 10))
                            .entryDtos())
                    .extracting(ActivityEntryDto::id)
                    .containsExactly("5", "4", "3", "2", "1");
        }
    }

    @Test
    void closeStopsTheSchedulerAndClosesTheDurableStore() throws InterruptedException {
        FakeDurableStore durable = new FakeDurableStore();
        BufferedActivityStore store =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, Duration.ofMillis(15), 100);
        store.close();
        assertThat(durable.closed.get()).isTrue();

        store.append(stampedEntry(1, "1"));
        Thread.sleep(100); // well past several would-be flush cycles
        assertThat(durable.accepted).isEmpty();
    }

    @Test
    void closeFlushesPendingEntriesBeforeStopping() {
        FakeDurableStore durable = new FakeDurableStore();
        // A long interval means the periodic tick would never fire during this test on its own: any
        // entry that reaches durable.accepted must have come from close()'s own final flush.
        BufferedActivityStore store =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, LONG_INTERVAL, 100);
        store.append(stampedEntry(1, "1"));
        assertThat(durable.accepted).isEmpty();
        assertThat(store.pendingCount()).isEqualTo(1);

        store.close();

        assertThat(durable.accepted).extracting(StoredActivityEntry::seq).containsExactly(1L);
        assertThat(store.pendingCount()).isZero();
    }

    @Test
    void closeDoesNotBlockIndefinitelyWhenTheDurableStoreHangs() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        ActivityStore hangingDurable = new ActivityStore() {
            @Override
            public void appendBatch(List<StoredActivityEntry> entries) {
                try {
                    // Simulates a database that never responds (not a clean refusal/exception); release()
                    // is never called during the assertion, only afterward to let the daemon thread exit.
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public ActivityPage query(ActivityQuery query) {
                return new ActivityPage(List.of(), null, false);
            }
        };
        try {
            // A flush interval at/under the 2s floor makes the close-flush timeout exactly that floor.
            BufferedActivityStore store = new BufferedActivityStore(
                    new InMemoryActivityStore(10), hangingDurable, Duration.ofMillis(200), 100);
            store.append(stampedEntry(1, "1"));

            long startNanos = System.nanoTime();
            store.close();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            // Bounded well under what an indefinite hang would take; comfortably above the ~2s floor to
            // avoid test flakiness while still proving close() did not wait forever.
            assertThat(elapsedMillis).isLessThan(4000);
        } finally {
            release.countDown(); // let the stuck daemon thread unwind so it doesn't linger past the test
        }
    }

    @Test
    void schedulesPeriodicRetentionPruningWhenInstanceIdAndRetentionAreProvided() throws InterruptedException {
        FakeDurableStore durable = new FakeDurableStore();
        try (BufferedActivityStore store = new BufferedActivityStore(
                new InMemoryActivityStore(10), durable, Duration.ofMillis(10), 100, INSTANCE, Duration.ofMillis(50))) {
            waitUntil(() -> durable.pruneCalls.get() > 0, Duration.ofSeconds(2));
            assertThat(durable.pruneCalls.get()).isGreaterThan(0);
        }
    }

    @Test
    void doesNotSchedulePruningWhenInstanceIdOrRetentionIsAbsent() throws InterruptedException {
        FakeDurableStore durable = new FakeDurableStore();
        try (BufferedActivityStore store =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, Duration.ofMillis(10), 100)) {
            Thread.sleep(150);
            assertThat(durable.pruneCalls.get()).isZero();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return;
            }
            Thread.sleep(10);
        }
    }
}
