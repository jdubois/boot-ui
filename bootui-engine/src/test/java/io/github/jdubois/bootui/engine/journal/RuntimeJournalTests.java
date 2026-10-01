package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeJournalTests {

    private final List<RuntimeJournal> journals = new ArrayList<>();

    @AfterEach
    void closeJournals() {
        journals.forEach(RuntimeJournal::close);
    }

    @Test
    void aSubscriberIsToldOfEachRecordedBatchUntilItUnsubscribes() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        AtomicInteger changes = new AtomicInteger();
        Runnable unsubscribe = journal.subscribe(changes::incrementAndGet);

        journal.dispatchPending();
        assertThat(changes).as("an empty batch is no change").hasValue(0);
        journal.offer(sql(1, false));
        journal.offer(sql(2, false));
        journal.dispatchPending();
        assertThat(changes).hasValue(1);

        unsubscribe.run();
        journal.offer(sql(3, false));
        journal.dispatchPending();
        assertThat(changes).hasValue(1);
    }

    @Test
    void theDispatcherSequencesRetainsAndPublishesEveryAcceptedEventInOrder() throws Exception {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), true);
        List<Long> published = new CopyOnWriteArrayList<>();
        journal.addListener(entries -> entries.forEach(entry -> published.add(entry.sequence())));

        for (int i = 0; i < 5; i++) {
            assertThat(journal.offer(sql(i, false))).isTrue();
        }

        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        assertThat(published).containsExactly(1L, 2L, 3L, 4L, 5L);
        List<JournalEntry> entries = journal.entries();
        assertThat(entries).extracting(JournalEntry::sequence).containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(entries.get(4).event().epochMillis()).isEqualTo(1_000L);
        assertThat(journal.eventId(entries.get(0))).isEqualTo(journal.run().id() + "-5");
        JournalStatus status = journal.status();
        assertThat(status.lastSequence()).isEqualTo(5);
        assertThat(status.retainedEvents()).isEqualTo(5);
        assertThat(status.accepted()).isEqualTo(Map.of(JournalSource.SQL, 5L));
        assertThat(status.dropped()).isEmpty();
        assertThat(status.runId()).isEqualTo(journal.run().id());
        assertThat(status.instanceId()).isEqualTo(RunIdentity.instanceId());
    }

    @Test
    void itsDispatcherIsOneBootUiDaemonThread() throws Exception {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), true);
        List<Thread> threads = new CopyOnWriteArrayList<>();
        journal.addListener(entries -> threads.add(Thread.currentThread()));

        journal.offer(sql(1, false));

        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        assertThat(threads).singleElement().satisfies(thread -> {
            assertThat(thread.getName()).isEqualTo(RuntimeJournal.DISPATCHER_THREAD);
            assertThat(thread.isDaemon()).isTrue();
        });
    }

    @Test
    void aDisabledJournalOrADisabledSourceRecordsNothingAndDropsNothing() {
        RuntimeJournal disabled = journal(RuntimeJournalSettings.disabled(), false);
        RuntimeJournal sqlOff = journal(settings(100, 1_000_000, 100, 10, EnumSet.of(JournalSource.HTTP)), false);

        assertThat(disabled.offer(sql(1, false))).isFalse();
        assertThat(sqlOff.offer(sql(1, false))).isFalse();
        assertThat(sqlOff.offer(null)).isFalse();

        for (RuntimeJournal journal : List.of(disabled, sqlOff)) {
            journal.dispatchPending();
            assertThat(journal.entries()).isEmpty();
            assertThat(journal.status().accepted()).isEmpty();
            assertThat(journal.status().dropped()).isEmpty();
        }
        assertThat(disabled.status().enabled()).isFalse();
        assertThat(disabled.status().queueCapacity()).isZero();
    }

    @Test
    void workOnBootUisOwnThreadsIsNeverRecorded() throws Exception {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        CompletableFuture<Boolean> fromBootUi = new CompletableFuture<>();
        Thread bootUiThread =
                new Thread(() -> fromBootUi.complete(journal.offer(sql(1, false))), "bootui-activity-flush");
        bootUiThread.start();

        assertThat(fromBootUi.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(journal.offer(sql(2, false))).isTrue();
        journal.dispatchPending();
        assertThat(journal.entries())
                .singleElement()
                .extracting(entry -> entry.event().epochMillis())
                .isEqualTo(1_002L);
        assertThat(journal.status().dropped()).isEmpty();
    }

    @Test
    void theWorkOfBootUisOwnRequestsIsNeverRecordedWhereverTheAdapterSaysItRuns() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
            assertThat(journal.offer(sql(1, false))).isFalse();
        }
        journal.setCorrelationContextProvider(() -> CorrelationContext.BOOTUI);
        assertThat(journal.offer(sql(2, false)))
                .as("as the Quarkus adapter reads it from the Vert.x context")
                .isFalse();
        journal.setCorrelationContextProvider(null);
        assertThat(journal.offer(sql(3, false))).isTrue();

        assertThat(journal.status().dropped()).isEmpty();
        assertThat(CorrelationContext.BOOTUI.isEmpty()).isFalse();
        assertThat(CorrelationContext.BOOTUI.requestId()).isNull();
    }

    @Test
    void anEventOfferedOnItsOwnThreadGetsThatThreadsKindAndOthersKeepTheirs() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.setThreadKindClassifier(() -> ThreadKind.EVENT_LOOP);
        String here = Thread.currentThread().getName();

        journal.offer(new RuntimeEvent(JournalSource.LOG, 1, -1, null, null, null, null, here, null, false, null));
        journal.offer(new RuntimeEvent(JournalSource.LOG, 2, -1, null, null, null, null, "other", null, false, null));
        journal.offer(new RuntimeEvent(
                JournalSource.LOG, 3, -1, null, null, null, null, here, ThreadKind.WORKER, false, null));
        journal.offer(new RuntimeEvent(JournalSource.LOG, 4, -1, null, null, null, null, null, null, false, null));
        journal.dispatchPending();

        assertThat(journal.entries())
                .extracting(entry -> entry.event().threadKind())
                .containsExactly(null, ThreadKind.WORKER, null, ThreadKind.EVENT_LOOP);
    }

    @Test
    void theQueuesReservedTailAdmitsOnlyFailedOrSlowEvents() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 10, 20, JournalSource.all()), false);

        for (int i = 0; i < 10; i++) {
            journal.offer(sql(i, false));
        }
        boolean firstFailure = journal.offer(sql(10, true));
        boolean secondFailure = journal.offer(sql(11, true));
        boolean thirdFailure = journal.offer(sql(12, true));

        assertThat(firstFailure).isTrue();
        assertThat(secondFailure).isTrue();
        assertThat(thirdFailure).as("the queue is full").isFalse();
        JournalStatus status = journal.status();
        assertThat(status.queueDepth()).isEqualTo(10);
        assertThat(status.accepted()).containsEntry(JournalSource.SQL, 10L);
        assertThat(status.dropped()).containsEntry(JournalSource.SQL, 3L);
        assertThat(status.droppedTotal()).isEqualTo(3);
    }

    @Test
    void aStalledDispatcherNeverBlocksTheApplicationThreadAndItsDropsAreCounted() throws Exception {
        RuntimeJournal journal = journal(settings(1_000, 10_000_000, 1_000, 10, JournalSource.all()), true);
        CountDownLatch release = new CountDownLatch(1);
        journal.addListener(entries -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        long slowestOfferNanos = 0;
        long accepted = 0;
        long started = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            long before = System.nanoTime();
            if (journal.offer(sql(i, i % 100 == 0))) {
                accepted++;
            }
            slowestOfferNanos = Math.max(slowestOfferNanos, System.nanoTime() - before);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis)
                .as("20,000 offers against a stalled dispatcher")
                .isLessThan(5_000);
        assertThat(TimeUnit.NANOSECONDS.toMillis(slowestOfferNanos))
                .as("the slowest single offer")
                .isLessThan(500);
        JournalStatus status = journal.status();
        assertThat(accepted).isLessThanOrEqualTo(1_000 + RuntimeJournal.BATCH_SIZE);
        assertThat(status.accepted().get(JournalSource.SQL) + status.droppedTotal())
                .isEqualTo(20_000);
        assertThat(status.droppedTotal()).isGreaterThan(18_000);

        release.countDown();
        assertThat(journal.awaitDrained(Duration.ofSeconds(10))).isTrue();
        assertThat(journal.status().lastSequence()).isEqualTo(accepted);
    }

    @Test
    void listenersSeeEveryAcceptedEventIncludingThoseTheCountBoundEvicts() {
        RuntimeJournal journal = journal(settings(10, 10_000_000, 1_000, 10, JournalSource.all()), false);
        AtomicLong seen = new AtomicLong();
        journal.addListener(entries -> seen.addAndGet(entries.size()));

        for (int i = 0; i < 100; i++) {
            journal.offer(sql(i, false));
        }
        journal.dispatchPending();

        assertThat(seen.get()).isEqualTo(100);
        assertThat(journal.entries())
                .hasSize(10)
                .first()
                .extracting(JournalEntry::sequence)
                .isEqualTo(100L);
        JournalStatus status = journal.status();
        assertThat(status.evictedByCount()).isEqualTo(90);
        assertThat(status.evictedByBytes()).isZero();
        assertThat(status.bindingBound()).isEqualTo("COUNT");
        assertThat(status.oldestRetainedEpochMillis()).isEqualTo(1_000L + 90);
    }

    @Test
    void theByteBoundEvictsTooButNeverTheNewestEvent() {
        int eventBytes = sql(0, false).estimatedBytes();
        RuntimeJournal journal = journal(settings(1_000, eventBytes * 3L, 1_000, 10, JournalSource.all()), false);

        for (int i = 0; i < 10; i++) {
            journal.offer(sql(i, false));
        }
        journal.offer(new RuntimeEvent(
                JournalSource.SQL, 5_000, 1, null, null, null, null, "t", null, false, () -> eventBytes * 10));
        journal.dispatchPending();

        JournalStatus status = journal.status();
        assertThat(status.bindingBound()).isEqualTo("BYTES");
        assertThat(status.evictedByBytes()).isEqualTo(10);
        assertThat(journal.entries())
                .singleElement()
                .extracting(JournalEntry::sequence)
                .isEqualTo(11L);
    }

    @Test
    void failedAndSlowEventsKeepTheirReservedShareThroughARoutineFlood() {
        RuntimeJournal journal = journal(settings(10, 10_000_000, 1_000, 10, JournalSource.all()), false);

        journal.offer(sql(0, true));
        for (int i = 1; i <= 50; i++) {
            journal.offer(sql(i, false));
        }
        journal.dispatchPending();

        List<JournalEntry> entries = journal.entries();
        assertThat(entries).hasSize(10);
        assertThat(entries.get(9).sequence())
                .as("the failure is the oldest retained event")
                .isEqualTo(1L);
        assertThat(journal.status().reserved()).isEqualTo(1);
        assertThat(journal.status().reservedCapacity()).isEqualTo(1);
    }

    @Test
    void aFailingListenerIsCountedAndTheOthersStillSeeTheBatch() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        AtomicLong seen = new AtomicLong();
        journal.addListener(entries -> {
            throw new IllegalStateException("listener bug");
        });
        journal.addListener(entries -> seen.addAndGet(entries.size()));

        journal.offer(sql(1, false));
        journal.dispatchPending();

        assertThat(seen.get()).isEqualTo(1);
        assertThat(journal.status().listenerFailures()).isEqualTo(1);
        assertThat(journal.entries()).hasSize(1);
    }

    @Test
    void clearingDropsTheRetainedEventsButKeepsTheCountsAndTheSequence() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.offer(sql(1, false));
        journal.offer(sql(2, false));
        journal.dispatchPending();

        journal.clear();
        journal.offer(sql(3, false));
        journal.dispatchPending();

        assertThat(journal.entries())
                .singleElement()
                .extracting(JournalEntry::sequence)
                .isEqualTo(3L);
        assertThat(journal.status().accepted()).containsEntry(JournalSource.SQL, 3L);
    }

    @Test
    void theDictionaryCountsAgainstTheByteBound() {
        int eventBytes = sql(0, false).estimatedBytes();
        RuntimeJournal journal = journal(settings(1_000, eventBytes * 40L, 1_000, 10, JournalSource.all()), false);
        String longTemplate = "x".repeat(eventBytes * 5);
        assertThat(journal.dictionary().intern(longTemplate)).isZero();

        for (int i = 0; i < 40; i++) {
            journal.offer(sql(i, false));
        }
        journal.dispatchPending();

        JournalStatus status = journal.status();
        assertThat(status.dictionaryBytes()).isGreaterThan(eventBytes * 5L);
        assertThat(status.retainedBytes() + status.dictionaryBytes()).isLessThanOrEqualTo(eventBytes * 40L);
        assertThat(status.retainedEvents()).isLessThan(40);
    }

    @Test
    void awaitDrainedReportsATimeoutWhileTheDispatcherIsStalled() throws Exception {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.offer(sql(1, false));

        assertThat(journal.awaitDrained(Duration.ofMillis(20))).isFalse();
        journal.dispatchPending();
        assertThat(journal.awaitDrained(Duration.ofMillis(20))).isTrue();
    }

    private RuntimeJournal journal(RuntimeJournalSettings settings, boolean startDispatcher) {
        RuntimeJournal journal = new RuntimeJournal(settings, RunIdentity.start(), startDispatcher);
        journals.add(journal);
        return journal;
    }

    private static RuntimeJournalSettings settings(
            int maxEvents, long maxBytes, int queueCapacity, int reservedPercent, Set<JournalSource> sources) {
        return new RuntimeJournalSettings(
                true, maxEvents, maxBytes, queueCapacity, reservedPercent, reservedPercent, sources);
    }

    private static RuntimeEvent sql(int index, boolean failedOrSlow) {
        return RuntimeEvent.of(
                JournalSource.SQL,
                1_000L + index,
                1_000,
                CorrelationContext.forRequest("0123456789abcdef"),
                "http-nio-8080-exec-1",
                ThreadKind.WORKER,
                failedOrSlow,
                () -> 64);
    }
}
