package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeJournalTests {

    private final List<RuntimeJournal> journals = new ArrayList<>();

    @AfterEach
    void closeJournals() {
        journals.forEach(RuntimeJournal::close);
    }

    @Test
    void closedJournalRejectsNewListenersAndPublicationWithoutRunningTheCommit() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.close();
        assertThat(journal.settings().enabled()).isTrue();
        assertThat(journal.isOpen()).isFalse();
        assertThatThrownBy(() -> journal.addListener(entries -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> journal.addListener(new JournalAggregates()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        AtomicInteger committed = new AtomicInteger();
        assertThatThrownBy(() -> journal.commitWhileOpen(() -> {
                    committed.incrementAndGet();
                    return true;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThat(committed).hasValue(0);
    }

    @Test
    void disabledJournalStillAcceptsStartupListenersButCannotPublishNewCapture() {
        RuntimeJournal journal = journal(RuntimeJournalSettings.disabled(), false);
        journal.addListener(entries -> {});
        journal.addListener(new JournalAggregates());
        assertThat(journal.isOpen()).isFalse();
        assertThatThrownBy(() -> journal.commitWhileOpen(() -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
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
    void anAgentRecordBootUisDrainThreadPublishesIsRecordedButNoOtherSourceIs() throws Exception {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        RuntimeEvent caught = new RuntimeEvent(
                JournalSource.AGENT_CAUGHT_EXCEPTIONS,
                1,
                -1,
                "00000000000000ab",
                null,
                null,
                "http-nio-exec-1",
                null,
                false,
                new CaughtExceptionPayload(
                        CaughtExceptionPayload.CAUGHT,
                        "com.example.Shop",
                        "buy()V",
                        12,
                        List.of("java/io/IOException"),
                        0,
                        "java.io.IOException",
                        "io",
                        1L,
                        null,
                        null,
                        42));
        CompletableFuture<List<Boolean>> fromDrainer = new CompletableFuture<>();
        Thread drainer = new Thread(
                () -> fromDrainer.complete(List.of(
                        journal.offer(caught),
                        journal.offerAgentRecord(caught),
                        journal.offerAgentRecord(sql(2, false)))),
                "bootui-agent-drainer");
        drainer.start();

        assertThat(fromDrainer.get(5, TimeUnit.SECONDS)).containsExactly(false, true, false);
        journal.dispatchPending();
        assertThat(journal.entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.event().thread()).isEqualTo("http-nio-exec-1"));
    }

    @Test
    void aClassifiedImportedAiEventBypassesOnlyTheReceiversBootUiScope() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        RuntimeEvent ai = new RuntimeEvent(
                JournalSource.AI,
                1,
                1,
                null,
                null,
                "trace-1",
                null,
                null,
                false,
                new AiPayload("chat", "openai", "gpt-4o", 1L, 1L, "stop", false));

        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.BOOTUI)) {
            assertThat(journal.offer(ai)).isFalse();
            assertThat(journal.offerImported(ai)).isTrue();
            assertThat(journal.offerImported(sql(2, false)))
                    .as("only imported AI is allowed")
                    .isFalse();
        }
        journal.dispatchPending();

        assertThat(journal.entries())
                .singleElement()
                .extracting(entry -> entry.event())
                .isEqualTo(ai);
    }

    @Test
    void anEventOfferedOnItsOwnThreadGetsThatThreadsKindAndOthersKeepTheirs() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.setThreadKindClassifier(() -> ThreadKind.EVENT_LOOP);
        String here = Thread.currentThread().getName();

        journal.offer(new RuntimeEvent(JournalSource.LOG, 1, -1, null, null, null, here, null, false, null));
        journal.offer(new RuntimeEvent(JournalSource.LOG, 2, -1, null, null, null, "other", null, false, null));
        journal.offer(
                new RuntimeEvent(JournalSource.LOG, 3, -1, null, null, null, here, ThreadKind.WORKER, false, null));
        journal.offer(new RuntimeEvent(JournalSource.LOG, 4, -1, null, null, null, null, null, false, null));
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

    /**
     * §5.3's default history: at 88 events per second, the default 50,000-event bound keeps about 9.5 minutes once the
     * count bound binds first, where 1.19.0's panel buffers kept about 2.3 seconds, and the oldest retained time is
     * always reported.
     */
    @Test
    void theDefaultCountBoundKeepsAboutNineAndAHalfMinutesAt88EventsPerSecond() {
        RuntimeJournalSettings defaults = RuntimeJournalSettings.defaults();
        RuntimeJournal journal = journal(
                new RuntimeJournalSettings(
                        true,
                        defaults.maxEvents(),
                        1024L * 1024 * 1024,
                        defaults.queueCapacity(),
                        defaults.reservedSharePercent(),
                        defaults.reservedQueueSharePercent(),
                        defaults.sources()),
                false);
        int events = 88 * 600;
        long start = 1_000_000L;
        for (int i = 0; i < events; i++) {
            journal.offer(RuntimeEvent.of(
                    JournalSource.SQL,
                    start + i * 1_000L / 88,
                    1_000,
                    CorrelationContext.forRequest("0123456789abcdef"),
                    "http-nio-8080-exec-1",
                    ThreadKind.WORKER,
                    false,
                    () -> 64));
            if (i % 1_000 == 999) {
                journal.dispatchPending();
            }
        }
        journal.dispatchPending();

        JournalStatus status = journal.status();
        assertThat(status.bindingBound()).isEqualTo("COUNT");
        assertThat(journal.entries()).hasSize(RuntimeJournalSettings.DEFAULT_MAX_EVENTS);
        long newest = start + (events - 1) * 1_000L / 88;
        long kept = newest - status.oldestRetainedEpochMillis();
        assertThat(kept).as("about 9.5 minutes: 50,000 events at 88 per second").isBetween(560_000L, 570_000L);
        assertThat(status.oldestRetainedEpochMillis())
                .isEqualTo(journal.entries()
                        .get(journal.entries().size() - 1)
                        .event()
                        .epochMillis());
    }

    @Test
    void theByteBoundRejectsAnOversizedEventWithoutDisplacingOlderEvidence() {
        int eventBytes = sql(0, false).estimatedBytes();
        RuntimeJournal journal = journal(settings(1_000, eventBytes * 3L, 1_000, 10, JournalSource.all()), false);

        for (int i = 0; i < 10; i++) {
            journal.offer(sql(i, false));
        }
        journal.offer(new RuntimeEvent(
                JournalSource.SQL, 5_000, 1, null, null, null, "t", null, false, () -> eventBytes * 10));
        journal.dispatchPending();

        JournalStatus status = journal.status();
        assertThat(status.bindingBound()).isEqualTo("BYTES");
        assertThat(status.evictedByBytes()).isPositive();
        assertThat(journal.entries()).noneMatch(entry -> entry.sequence() == 11L);
        assertThat(journal.entries()).isNotEmpty();
        assertThat(status.retainedBytes() + status.dictionaryBytes()).isLessThanOrEqualTo(eventBytes * 3L);
    }

    @Test
    void anOversizedFirstEventIsStillDeliveredToAggregatesButNotRetained() {
        RuntimeJournal journal = journal(settings(10, 100, 1_000, 10, JournalSource.all()), false);
        AtomicLong observed = new AtomicLong();
        journal.addListener(entries -> observed.addAndGet(entries.size()));

        journal.offer(sql(0, false));
        journal.dispatchPending();

        assertThat(observed.get()).isEqualTo(1);
        assertThat(journal.entries()).isEmpty();
        assertThat(journal.status().evictedByBytes()).isEqualTo(1);
        assertThat(journal.status().retainedBytes() + journal.status().dictionaryBytes())
                .isLessThanOrEqualTo(100);
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
    void theLossHorizonIsTheLatestStartOfAnEvictedEventOfAUnitOfWork() {
        RuntimeJournal journal = journal(settings(3, 10_000_000, 100, 0, JournalSource.all()), false);
        assertThat(journal.lossHorizonMillis()).isNull();

        journal.offer(sql(5, false));
        journal.offer(statement(9, "select 1"));
        journal.offer(sql(2, false));
        journal.offer(sql(3, false));
        journal.dispatchPending();
        assertThat(journal.lossHorizonMillis()).as("one event evicted").isEqualTo(1_005L);

        journal.offer(sql(4, false));
        journal.dispatchPending();
        assertThat(journal.lossHorizonMillis())
                .as("an event that belongs to no request, execution, or trace leaves it where it was")
                .isEqualTo(1_005L);
        journal.offer(sql(6, false));
        journal.dispatchPending();
        assertThat(journal.lossHorizonMillis()).as("it never moves back").isEqualTo(1_005L);
    }

    @Test
    void clearingMovesTheLossHorizonPastTheRetainedAndTheStillQueuedEvents() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.offer(sql(1, false));
        journal.dispatchPending();
        journal.offer(sql(7, false));

        journal.clear();

        assertThat(journal.lossHorizonMillis()).isEqualTo(1_007L);
        journal.offer(sql(9, false));
        journal.dispatchPending();
        assertThat(journal.entries()).hasSize(1);
        assertThat(journal.lossHorizonMillis()).isEqualTo(1_007L);
    }

    @Test
    void clearingARequestsHttpEventRemembersWhenThatRequestEnded() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.offer(sql(1, false));
        journal.dispatchPending();
        assertThat(journal.lostRequestEndMillis())
                .as("a statement is not a request's end")
                .isNull();
        journal.offer(new RuntimeEvent(
                JournalSource.HTTP, 2_000, 30_000_000, "r1", null, null, "http-1", null, true, () -> 64));
        journal.dispatchPending();

        journal.clear();

        assertThat(journal.lostRequestEndMillis()).isEqualTo(2_030L);
        assertThat(journal.lossHorizonMillis()).isEqualTo(2_000L);
    }

    @Test
    void itRemembersTheThreadsOfLostRequestsAcrossAClearButNotThoseOfUnownedEvents() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.offer(sql(1, false));
        journal.offer(new RuntimeEvent(JournalSource.LOG, 1_002, 0, null, null, null, "main", null, true, () -> 64));
        journal.dispatchPending();

        journal.clear();
        journal.offer(sql(3, false));
        journal.dispatchPending();

        assertThat(journal.lostARequestOn("http-nio-8080-exec-1")).isTrue();
        assertThat(journal.lostARequestOn("main"))
                .as("a startup error carried no request")
                .isFalse();
        assertThat(journal.lostARequestOn(null)).isFalse();
    }

    @Test
    void theLossHorizonNeverMovesPastNow() {
        RuntimeJournal journal = journal(settings(1, 10_000_000, 100, 0, JournalSource.all()), false);
        long future = System.currentTimeMillis() + java.time.Duration.ofDays(1).toMillis();

        // An imported span from a skewed clock, then an event that evicts it.
        journal.offer(new RuntimeEvent(JournalSource.AI, future, 1, null, null, "trace-1", "t", null, false, () -> 64));
        journal.offer(sql(1, false));
        journal.dispatchPending();

        assertThat(journal.lossHorizonMillis()).isNotNull().isLessThanOrEqualTo(System.currentTimeMillis());
    }

    @Test
    void anEventTooLargeToRetainMovesTheLossHorizon() {
        int eventBytes = sql(0, false).estimatedBytes();
        RuntimeJournal journal = journal(settings(1_000, eventBytes * 3L, 1_000, 10, JournalSource.all()), false);

        journal.offer(new RuntimeEvent(
                JournalSource.SQL, 5_000, 1, "r1", null, null, "t", null, false, () -> eventBytes * 10));
        journal.dispatchPending();

        assertThat(journal.lossHorizonMillis()).isEqualTo(5_000L);
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
    void offloadingCountsTheRetainedAndTheStillQueuedEventsItDrops() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.offer(sql(1, false));
        journal.offer(sql(2, false));
        journal.dispatchPending();
        journal.offer(sql(3, false));

        assertThat(journal.offloadRetainedData()).isEqualTo(3L);
        assertThat(journal.entries()).isEmpty();
        assertThat(journal.status().queueDepth()).isZero();
    }

    @Test
    void clearingAlsoDropsTheQueuedEventsAndTellsEveryListener() throws InterruptedException {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        List<String> seen = new ArrayList<>();
        journal.addListener(new JournalListener() {
            @Override
            public void onEntries(List<JournalEntry> entries) {
                seen.add("batch of " + entries.size());
            }

            @Override
            public void onClear() {
                seen.add("cleared");
            }
        });
        journal.offer(sql(1, false));
        journal.offer(sql(2, false));

        journal.clear();
        journal.dispatchPending();

        assertThat(journal.entries()).isEmpty();
        assertThat(seen)
                .as("no event recorded before the clear is processed after it")
                .containsExactly("cleared");
        assertThat(journal.awaitDrained(java.time.Duration.ZERO)).isTrue();
    }

    @Test
    void stampingAndOfferingAreAtomicWithClearingTheQueue() throws Exception {
        CountDownLatch stamped = new CountDownLatch(1);
        CountDownLatch releaseOffer = new CountDownLatch(1);
        AtomicReference<Boolean> offered = new AtomicReference<>();
        AtomicLong cleared = new AtomicLong(-1);
        RuntimeJournal journal = new RuntimeJournal(
                settings(100, 1_000_000, 100, 10, JournalSource.all()), RunIdentity.start(), false, () -> {
                    stamped.countDown();
                    try {
                        releaseOffer.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
        journals.add(journal);

        Thread offering = new Thread(() -> offered.set(journal.offer(sql(1, false))));
        offering.start();
        assertThat(stamped.await(5, TimeUnit.SECONDS)).isTrue();

        Thread clearing = new Thread(() -> cleared.set(journal.offloadRetainedData()));
        clearing.start();
        while (clearing.getState() != Thread.State.WAITING && clearing.isAlive()) {
            Thread.onSpinWait();
        }
        assertThat(clearing.isAlive())
                .as("the clear waits for the offer's admission")
                .isTrue();

        releaseOffer.countDown();
        offering.join(5_000);
        clearing.join(5_000);

        assertThat(offered.get()).isTrue();
        assertThat(cleared.get()).isEqualTo(1);
        assertThat(journal.status().queueDepth()).isZero();
        assertThat(journal.status().dropped()).isEmpty();
        journal.dispatchPending();
        assertThat(journal.entries()).isEmpty();
    }

    @Test
    void anOfferDuringListenerClearingUsesTheNewRecordingWithoutWaitingForTheListener() throws Exception {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        CountDownLatch clearingListener = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        journal.addListener(new JournalListener() {
            @Override
            public void onEntries(List<JournalEntry> entries) {}

            @Override
            public void onClear() {
                clearingListener.countDown();
                try {
                    releaseListener.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread clearing = new Thread(journal::clear);
        clearing.start();
        assertThat(clearingListener.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> offered = CompletableFuture.supplyAsync(() -> journal.offer(sql(1, false)));
        assertThat(offered.get(1, TimeUnit.SECONDS)).isTrue();
        releaseListener.countDown();
        clearing.join(5_000);
        journal.dispatchPending();

        assertThat(journal.entries())
                .singleElement()
                .extracting(entry -> entry.event().epochMillis())
                .isEqualTo(1_001L);
    }

    @Test
    void anOfferDoesNotWaitForTheDetachedQueueToDrain() throws Exception {
        CountDownLatch beforeDrain = new CountDownLatch(1);
        CountDownLatch releaseDrain = new CountDownLatch(1);
        AtomicInteger detachedDepth = new AtomicInteger();
        RuntimeJournal journal = new RuntimeJournal(
                settings(100, 1_000_000, 100, 10, JournalSource.all()), RunIdentity.start(), false, null, queued -> {
                    detachedDepth.set(queued);
                    beforeDrain.countDown();
                    try {
                        releaseDrain.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
        journals.add(journal);
        journal.offer(sql(1, false));

        AtomicLong cleared = new AtomicLong(-1);
        Thread clearing = new Thread(() -> cleared.set(journal.offloadRetainedData()));
        clearing.start();
        assertThat(beforeDrain.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(detachedDepth).hasValue(1);
        assertThat(journal.awaitDrained(Duration.ZERO))
                .as("the old accepted event has not been drained yet")
                .isFalse();

        CompletableFuture<Boolean> offered = CompletableFuture.supplyAsync(() -> journal.offer(sql(2, false)));
        assertThat(offered.get(1, TimeUnit.SECONDS)).isTrue();
        assertThat(journal.status().queueDepth()).isEqualTo(1);

        releaseDrain.countDown();
        clearing.join(5_000);
        assertThat(cleared).hasValue(1);
        journal.dispatchPending();
        assertThat(journal.entries())
                .singleElement()
                .extracting(entry -> entry.event().epochMillis())
                .isEqualTo(1_002L);
    }

    @Test
    void offersRacingClearsAreNeitherLostNorReorderedAndEveryOneIsCounted() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                settings(100_000, 100_000_000, 256, 10, JournalSource.all()), RunIdentity.start(), true, null, null, 5);
        journals.add(journal);
        List<long[]> seen = new ArrayList<>();
        journal.addListener(entries -> {
            for (JournalEntry entry : entries) {
                seen.add(new long[] {entry.sequence(), entry.event().epochMillis()});
            }
        });
        int producers = 4;
        int perProducer = 20_000;
        AtomicLong acceptedOffers = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int p = 0; p < producers; p++) {
            long base = p * 1_000_000L;
            Thread producer = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perProducer; i++) {
                    if (journal.offer(
                            RuntimeEvent.of(JournalSource.SQL, base + i, 1, null, null, null, i % 7 == 0, () -> 64))) {
                        acceptedOffers.incrementAndGet();
                    }
                }
            });
            producer.start();
            threads.add(producer);
        }
        start.countDown();
        int clears = 0;
        while (threads.stream().anyMatch(Thread::isAlive)) {
            journal.clear();
            clears++;
            Thread.onSpinWait();
        }
        for (Thread thread : threads) {
            thread.join(30_000);
        }

        assertThat(journal.awaitDrained(Duration.ofSeconds(10)))
                .as("every accepted offer is processed or cleared, none stranded on a detached queue")
                .isTrue();
        JournalStatus status = journal.status();
        long accepted =
                status.accepted().values().stream().mapToLong(Long::longValue).sum();
        assertThat(accepted).isEqualTo(acceptedOffers.get());
        assertThat(accepted + status.droppedTotal()).isEqualTo((long) producers * perProducer);
        assertThat(status.clears()).isEqualTo(clears);
        long lastSequence = 0;
        long[] lastPerProducer = {-1, -1, -1, -1};
        for (long[] entry : seen) {
            assertThat(entry[0]).isGreaterThan(lastSequence);
            lastSequence = entry[0];
            int producer = (int) (entry[1] / 1_000_000L);
            assertThat(entry[1])
                    .as("each producer's events stay in offer order")
                    .isGreaterThan(lastPerProducer[producer]);
            lastPerProducer[producer] = entry[1];
        }
    }

    @Test
    void anEventOfferedAfterTheRunEndedIsDroppedAndCountedEvenAfterAClear() throws Exception {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.offer(sql(1, false));

        journal.close();
        assertThat(journal.entries()).as("processed as the run ended").hasSize(1);

        assertThat(journal.offer(sql(2, true))).isFalse();
        journal.clear();
        assertThat(journal.offer(sql(3, false))).isFalse();
        assertThat(journal.status().droppedTotal()).isEqualTo(2);
        assertThat(journal.status().queueDepth()).isZero();
        assertThat(journal.awaitDrained(Duration.ZERO)).isTrue();
    }

    @Test
    void aSmallQueueKeepsUpWithRepeatedBurstsDespiteTheDispatchersPause() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                settings(100_000, 100_000_000, 100, 10, JournalSource.all()), RunIdentity.start(), true, null, null, 5);
        journals.add(journal);
        int offered = 0;
        for (int burst = 0; burst < 200; burst++) {
            for (int i = 0; i < 60; i++) {
                journal.offer(sql(offered++, false));
            }
            // A burst of 60 into a routine share of 90 drops nothing as long as the dispatcher keeps up.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (journal.status().queueDepth() > 30 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }

        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        assertThat(journal.status().droppedTotal()).isZero();
        assertThat(journal.lastSequence()).isEqualTo(offered);
    }

    @Test
    void aBurstEndsTheDispatchersPauseEvenWhenAListenerParkedMeanwhile() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                settings(100_000, 100_000_000, 100, 10, JournalSource.all()),
                RunIdentity.start(),
                true,
                null,
                null,
                5,
                TimeUnit.SECONDS.toNanos(30));
        journals.add(journal);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        journal.addListener(entries -> {
            if (entered.getCount() > 0) {
                entered.countDown();
                try {
                    // A listener parking, as on a contended lock, while the burst arrives.
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        journal.offer(sql(0, false));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // Half the routine share of 90: the dispatcher's 30-second pause after its one-event batch must end at once.
        for (int i = 1; i <= 45; i++) {
            assertThat(journal.offer(sql(i, false))).isTrue();
        }
        release.countDown();

        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        assertThat(journal.status().droppedTotal()).isZero();
    }

    @Test
    void aCloseWithAStuckDispatcherRefusesLaterOffersAndReleasesTheQueuedEvents() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                settings(100, 1_000_000, 100, 10, JournalSource.all()), RunIdentity.start(), true, null, null, 5);
        journals.add(journal);
        CountDownLatch entered = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean release = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        journal.addListener(new JournalListener() {
            @Override
            public void onEntries(List<JournalEntry> entries) {
                entered.countDown();
                // Stuck past close()'s two-second join, deaf to its interrupt.
                while (!release.get()) {
                    Thread.interrupted();
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            }

            @Override
            public void onClose() {
                closes.incrementAndGet();
            }
        });
        try {
            journal.offer(sql(0, false));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 1; i <= 3; i++) {
                assertThat(journal.offer(sql(i, false))).isTrue();
            }

            journal.close();

            assertThat(journal.status().queueDepth())
                    .as("the queued events are released")
                    .isZero();
            assertThat(journal.lossHorizonMillis())
                    .as("released, not dropped, so the work they belonged to lost them")
                    .isEqualTo(1_003L);
            assertThat(journal.offer(sql(4, true))).isFalse();
            assertThat(journal.offer(sql(5, false))).isFalse();
            assertThat(journal.status().droppedTotal()).isEqualTo(2);
        } finally {
            release.set(true);
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(5)))
                .as("the stuck batch and the released events are all accounted for")
                .isTrue();
        assertThat(closes)
                .as("listeners are not told a run ended while its batch is stuck")
                .hasValue(0);
    }

    @Test
    void theRunningDispatcherContinuesWithTheReplacementQueueAfterAClear() throws Exception {
        RuntimeJournal journal = new RuntimeJournal(
                settings(100, 1_000_000, 100, 10, JournalSource.all()), RunIdentity.start(), true, null, null, 30_000);
        journals.add(journal);
        journal.offer(sql(1, false));
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean waiting = false;
        while (!waiting && System.nanoTime() < deadline) {
            // Waiting in the queue's poll, not in the pause after its batch.
            waiting = Thread.getAllStackTraces().entrySet().stream()
                    .filter(thread -> thread.getKey().getName().equals(RuntimeJournal.DISPATCHER_THREAD))
                    .anyMatch(thread -> thread.getKey().getState() == Thread.State.TIMED_WAITING
                            && java.util.Arrays.stream(thread.getValue())
                                    .anyMatch(frame -> frame.getClassName().equals(JournalQueue.class.getName())
                                            && frame.getMethodName().equals("poll")));
            Thread.onSpinWait();
        }
        assertThat(waiting)
                .as("the dispatcher is polling the queue being detached")
                .isTrue();

        journal.clear();
        journal.offer(sql(2, false));

        assertThat(journal.awaitDrained(Duration.ofSeconds(1)))
                .as("the detached queue wakes the dispatcher before its poll timeout")
                .isTrue();
        assertThat(journal.entries())
                .singleElement()
                .extracting(entry -> entry.event().epochMillis())
                .isEqualTo(1_002L);
    }

    @Test
    void anEventTheDispatcherTookAsTheRecordingWasClearedIsNotRecorded() throws Exception {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), true);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        journal.addListener(entries -> {
            if (blocked.getCount() > 0) {
                blocked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        journal.offer(sql(1, false));
        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        // Queued while the first batch is processed; the dispatcher may take it before the clear gets in.
        journal.offer(sql(2, false));
        Thread clearing = new Thread(journal::clear);
        clearing.start();
        while (clearing.getState() != Thread.State.BLOCKED && clearing.isAlive()) {
            Thread.onSpinWait();
        }
        release.countDown();
        clearing.join(5_000);

        assertThat(journal.awaitDrained(java.time.Duration.ofSeconds(5))).isTrue();
        assertThat(journal.entries())
                .as("offered before the clear, so cleared with it")
                .isEmpty();
        assertThat(journal.status().dropped())
                .as("a user clear is not queue-pressure loss")
                .isEmpty();
        assertThat(journal.lossHorizonMillis())
                .as("cleared from the queue or taken by the dispatcher, the second event was lost to the clear")
                .isEqualTo(1_002L);
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
    void clearingEmptiesTheDictionaryAndARepeatedStatementIsSharedAgain() {
        RuntimeJournal journal = journal(settings(100, 1_000_000, 100, 10, JournalSource.all()), false);
        journal.offer(statement(1, "select * from orders where id = ?"));
        journal.dispatchPending();
        assertThat(journal.status().dictionaryEntries()).isPositive();

        journal.clear();

        assertThat(journal.status().dictionaryEntries()).isZero();
        assertThat(journal.status().dictionaryBytes()).isZero();

        journal.offer(statement(2, "select * from customers where id = ?"));
        journal.offer(statement(3, "select * from customers where id = ?"));
        journal.dispatchPending();

        List<JournalEntry> entries = journal.entries();
        assertThat(entries).hasSize(2);
        assertThat(((SqlPayload) entries.get(0).event().payload()).sql())
                .isSameAs(((SqlPayload) entries.get(1).event().payload()).sql());
        assertThat(journal.status().dictionaryEntries()).isEqualTo(1);
        assertThat(journal.status().dictionaryBytes()).isPositive();
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

    private static RuntimeEvent statement(int index, String sql) {
        return RuntimeEvent.of(
                JournalSource.SQL,
                1_000L + index,
                1_000,
                null,
                null,
                null,
                false,
                new SqlPayload(new String(sql), null, null, false));
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
