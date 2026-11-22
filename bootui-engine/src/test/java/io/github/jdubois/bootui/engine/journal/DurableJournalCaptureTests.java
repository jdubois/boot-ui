package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DurableJournalCaptureTests {

    private final List<RuntimeJournal> journals = new ArrayList<>();
    private final List<DurableJournalCapture> captures = new ArrayList<>();

    @Test
    void startBackfillsTheRetainedSnapshotAndThenCapturesFutureBatchesWithoutAGap() throws Exception {
        RuntimeJournal journal = journal();
        journal.offer(event(10, "retained"));
        journal.dispatchPending();
        RecordingWriter writer = new RecordingWriter();

        DurableJournalIdentity identity =
                new DurableJournalIdentity("tenant", "orders", "durable-run-uuid", "1234", "instance-7");
        DurableJournalCapture capture = DurableJournalCapture.start(
                journal,
                identity,
                writer,
                new JournalActivityFeed(0, 2, null),
                settings(32, 8_000, 32, 8, seconds(60), seconds(60)));
        captures.add(capture);
        journal.offer(event(20, "future"));
        journal.dispatchPending();

        capture.flushNow();
        await(() -> writer.entries.stream().map(JournalEntry::sequence).toList().equals(List.of(1L, 2L)));
        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L, 2L);
        assertThat(writer.entries.get(0)).isEqualTo(journal.entries().get(1));
        assertThat(writer.events)
                .extracting(DurableJournalEvent::activity)
                .allSatisfy(activity -> assertThat(activity).isNotNull());
        assertThat(writer.events)
                .extracting(event -> event.activity().id())
                .contains("durable-run-uuid-1", "durable-run-uuid-2");
        assertThat(writer.scopes).allMatch(scope -> scope.equals(identity.scope()));
        assertThat(writer.runs).filteredOn(run -> run.instanceId() != null).allSatisfy(run -> {
            assertThat(run.scope()).isEqualTo(identity.scope());
            assertThat(run.instanceId()).isEqualTo(identity.instanceId());
        });
        assertThat(capture.status().durableWatermark()).isEqualTo(2);
    }

    @Test
    void quietDatasourceActivationBackfillsMoreThanTheDefaultPendingCapacity() throws Exception {
        int retained = 600;
        RuntimeJournal journal = journal(1_000);
        for (int sequence = 1; sequence <= retained; sequence++) {
            assertThat(journal.offer(event(sequence, "request-" + sequence))).isTrue();
            if (sequence % 100 == 0) {
                journal.dispatchPending();
            }
        }
        assertThat(journal.entries()).hasSize(retained);
        DurableJournalSettings durable = new DurableJournalSettings(
                "local",
                "orders",
                journal.run().id(),
                "1234",
                "instance",
                false,
                DurableJournalSettings.DEFAULT_FLUSH_INTERVAL,
                DurableJournalSettings.DEFAULT_MAX_PENDING_ENTRIES,
                DurableJournalSettings.DEFAULT_MAX_PENDING_BYTES,
                DurableJournalSettings.DEFAULT_HOT_MAX_ENTRIES,
                DurableJournalSettings.DEFAULT_TABLE_PREFIX,
                DurableJournalSettings.DEFAULT_RETENTION,
                false);
        RecordingWriter writer = new RecordingWriter();
        DurableJournalCapture capture =
                DurableJournalCapture.start(journal, durable, writer, new JournalActivityFeed(0, 2, null));
        captures.add(capture);

        await(() -> writer.checkpoints.contains((long) retained));

        assertThat(writer.entries.size())
                .as("persisted rows (capture dropped %s)", capture.status().dropped())
                .isEqualTo(retained);
        assertThat(writer.entries)
                .extracting(JournalEntry::sequence)
                .containsExactlyElementsOf(
                        LongStream.rangeClosed(1, retained).boxed().toList());
        assertThat(capture.status().dropped()).isZero();
    }

    @Test
    void liveCompletionDuringBlockedBackfillKeepsSequenceAndProjection() throws Exception {
        RuntimeJournal journal = journal();
        assertThat(journal.offer(event(1, "retained"))).isTrue();
        assertThat(journal.offer(completion(2, "retained"))).isTrue();
        journal.dispatchPending();
        // The startup prepass still sees a SELECT dispatched after the retained completion.
        assertThat(journal.offer(event(3, "retained"))).isTrue();
        assertThat(journal.offer(event(4, "live"))).isTrue();
        assertThat(journal.offer(event(5, "live"))).isTrue();
        journal.dispatchPending();
        RecordingWriter writer = new RecordingWriter();
        writer.blockAppend = new CountDownLatch(1);
        DurableJournalCapture capture = capture(journal, writer, settings(2, 2_000, 2, 1, seconds(60), seconds(60)));
        try {
            assertThat(writer.appendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(journal.offer(completion(6, "live"))).isTrue();
            journal.dispatchPending();

            assertThat(writer.entries).isEmpty();
            assertThat(capture.status().durableWatermark()).isZero();
            assertThat(capture.status().pending()).isEqualTo(2);
            assertThat(capture.status().pendingBytes()).isBetween(1L, 2_000L);
        } finally {
            writer.blockAppend.countDown();
        }
        await(() -> writer.checkpoints.contains(6L));

        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(writer.checkpoints).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(writer.events.get(1).activity().sqlNPlusOneSuspected()).isTrue();
        assertThat(writer.events.get(5).activity().sqlNPlusOneSuspected()).isTrue();
        assertThat(capture.status().dropped()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFullLiveQueueShedsOnlyItsOldestRowToResumeBackfill(boolean byteLimited) throws Exception {
        RuntimeJournal journal = journal();
        retainFive(journal);
        RecordingWriter writer = new RecordingWriter();
        writer.blockAfterFirstAcknowledgement = new CountDownLatch(1);
        // 2,600 bytes holds three of these rows, not four. On that path the count cap leaves headroom.
        int countLimit = byteLimited ? 4 : 3;
        long byteLimit = byteLimited ? 2_600 : 8_000;
        DurableJournalCapture capture =
                capture(journal, writer, settings(countLimit, byteLimit, 3, 1, seconds(60), seconds(60)));
        try {
            assertThat(writer.firstAcknowledgementEntered.await(5, TimeUnit.SECONDS))
                    .isTrue();
            // The first retained row is acknowledged, but the worker has not taken its next chunk.
            for (int sequence = 6; sequence <= 8; sequence++) {
                assertThat(journal.offer(event(sequence, "live-" + sequence))).isTrue();
            }
            journal.dispatchPending();

            assertThat(capture.status().pending()).isEqualTo(3);
            assertThat(capture.status().pendingBytes()).isLessThanOrEqualTo(byteLimit);
            assertThat(capture.status().dropped()).isZero();
            assertThat(capture.status().durableWatermark()).isEqualTo(1);
        } finally {
            writer.blockAfterFirstAcknowledgement.countDown();
        }
        await(() -> writer.checkpoints.contains(8L));

        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L, 2L, 3L, 4L, 5L, 7L, 8L);
        assertThat(writer.checkpoints).containsExactly(1L, 2L, 3L, 4L, 5L, 7L, 8L);
        assertThat(capture.status().dropped()).isEqualTo(1L);
        assertThat(journal.lossHorizonMillis()).isEqualTo(6L);
    }

    @Test
    void anIndividuallyOversizedSeedRowDoesNotDisplaceItsNeighbors() throws Exception {
        RuntimeJournal journal = journal();
        assertThat(journal.offer(event(1, "first"))).isTrue();
        assertThat(journal.offer(event(2, "long-request-id-" + "x".repeat(3_000))))
                .isTrue();
        assertThat(journal.offer(event(3, "third"))).isTrue();
        journal.dispatchPending();
        assertThat(journal.entries()).hasSize(3);
        RecordingWriter writer = new RecordingWriter();
        DurableJournalCapture capture = capture(journal, writer, settings(10, 2_000, 10, 10, seconds(60), seconds(60)));
        await(() -> writer.checkpoints.contains(3L));

        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L, 3L);
        assertThat(capture.status().dropped()).isEqualTo(1L);
        assertThat(capture.status().quarantined()).isZero();
        assertThat(journal.lossHorizonMillis()).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clearDiscardsTheRemainingBackfillRegardlessOfTheInFlightAppendResult(boolean failAppend) throws Exception {
        RuntimeJournal journal = journal();
        retainFive(journal);
        RecordingWriter writer = new RecordingWriter();
        writer.failAppends.set(failAppend ? 1 : 0);
        writer.blockAppend = new CountDownLatch(1);
        DurableJournalCapture capture = capture(journal, writer, settings(2, 2_000, 2, 1, seconds(60), seconds(60)));
        try {
            assertThat(writer.appendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            journal.clear();
            assertThat(journal.offer(event(6, "after-clear"))).isTrue();
            journal.dispatchPending();
            assertThat(capture.status().pending()).isEqualTo(1);
        } finally {
            writer.blockAppend.countDown();
        }
        await(() -> writer.checkpoints.contains(6L));

        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(6L);
        assertThat(writer.operations)
                .filteredOn(operation -> !"checkpoint".equals(operation))
                .containsExactly("append", "clear", "append");
        assertThat(capture.status().dropped()).isZero();
    }

    @Test
    void backfillRetriesAnEarlierChunkBeforeAdvancingItsCursor() throws Exception {
        RuntimeJournal journal = journal();
        retainFive(journal);
        RecordingWriter writer = new RecordingWriter();
        writer.failAppends.set(1);
        DurableJournalCapture capture = capture(journal, writer, settings(2, 2_000, 2, 2, millis(10), seconds(60)));
        await(() -> writer.checkpoints.contains(5L));

        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(writer.checkpoints).containsExactly(2L, 4L, 5L);
        assertThat(writer.attempts).hasValue(4);
        assertThat(capture.status().lastError()).contains("the chunk will be retried");
        assertThat(capture.status().dropped()).isZero();
    }

    @Test
    void closeDrainsTheRemainingBackfillAfterTheFirstAppendUnblocks() throws Exception {
        RuntimeJournal journal = journal();
        retainFive(journal);
        RecordingWriter writer = new RecordingWriter();
        writer.blockAppend = new CountDownLatch(1);
        DurableJournalCapture capture = capture(journal, writer, settings(2, 2_000, 2, 1, seconds(60), seconds(60)));
        Thread closer = new Thread(capture::close);
        try {
            assertThat(writer.appendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            closer.start();
            await(() -> capture.status().state().equals("CLOSING"));
            assertThat(capture.status().finalFlushCompleted()).isFalse();
        } finally {
            writer.blockAppend.countDown();
            closer.join(5_000);
        }

        assertThat(closer.isAlive()).isFalse();
        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(writer.runs).last().extracting(DurableJournalRun::status).isEqualTo("STOPPED");
        assertThat(capture.status().finalFlushCompleted()).isTrue();
    }

    @Test
    void aCloseTimeoutDoesNotCallAnUndrainedBackfillComplete() throws Exception {
        RuntimeJournal journal = journal();
        retainFive(journal);
        RecordingWriter writer = new RecordingWriter();
        writer.blockAppend = new CountDownLatch(1);
        DurableJournalCapture capture = capture(
                journal,
                writer,
                new DurableJournalCaptureSettings(2, 2_000, 2, 2_000, 1, seconds(60), seconds(60), millis(100)));
        try {
            assertThat(writer.appendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            capture.close();

            assertThat(capture.status().state()).isEqualTo("CLOSED");
            assertThat(capture.status().finalFlushCompleted()).isFalse();
            assertThat(capture.status().durableWatermark()).isZero();
            assertThat(writer.entries).isEmpty();
        } finally {
            writer.blockAppend.countDown();
        }
    }

    @Test
    void aBurstBeyondCountAndByteCapacityShedsRowsWithoutExceedingTheBound() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        writer.blockAppend = new CountDownLatch(1);
        DurableJournalCapture capture = capture(journal, writer, settings(2, 20_000, 1, 1, seconds(60), seconds(60)));
        capture.onEntries(List.of(entry(1, 10, "first")));
        assertThat(writer.appendStarted.await(5, TimeUnit.SECONDS)).isTrue();

        List<JournalEntry> burst = new ArrayList<>();
        for (int sequence = 2; sequence < 100; sequence++) {
            burst.add(entry(sequence, sequence, "request-" + sequence));
        }
        capture.onEntries(burst);

        assertThat(capture.status().pending()).isLessThanOrEqualTo(2);
        assertThat(capture.status().pendingBytes()).isLessThanOrEqualTo(20_000);
        assertThat(capture.status().dropped()).isGreaterThan(90);
        writer.blockAppend.countDown();
    }

    @Test
    void aTransientBackendOutageRequeuesAndRetriesTheWholeOriginalSequenceBatch() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        writer.failAppends.set(3);
        DurableJournalCapture capture =
                capture(journal, writer, settings(64, 16_000, 64, 8, Duration.ofMillis(10), seconds(60)));

        journal.offer(event(10, "one"));
        journal.offer(event(20, "two"));
        journal.dispatchPending();

        await(() -> capture.status().durableWatermark() == 2);
        assertThat(writer.attempts.get()).isGreaterThanOrEqualTo(4);
        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L, 2L);
        assertThat(capture.status().pending()).isZero();
        assertThat(capture.status().lastError()).contains("retried");
    }

    @Test
    void retryTransfersInFlightOwnershipBeforeApplyingThePendingBound() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        writer.failAppends.set(1);
        DurableJournalCapture capture =
                capture(journal, writer, settings(2, 20_000, 2, 2, Duration.ofMillis(10), seconds(60)));

        journal.offer(event(10, "one"));
        journal.offer(event(20, "two"));
        journal.dispatchPending();

        await(() -> capture.status().durableWatermark() == 2);
        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L, 2L);
        assertThat(capture.status().pending()).isZero();
        assertThat(capture.status().dropped()).isZero();
    }

    @Test
    void durableProjectionStateIsBoundedEvenWithoutCompletionEvents() {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalCapture capture = capture(journal, writer, settings(32, 20_000, 32, 8, seconds(60), seconds(60)));

        for (int sequence = 1; sequence <= JournalActivityCapture.MAX_PENDING_REQUESTS * 2; sequence++) {
            capture.onEntries(List.of(entry(sequence, sequence, "request-" + sequence)));
        }

        assertThat(capture.pendingProjectionOwners())
                .isLessThanOrEqualTo(
                        JournalActivityCapture.MAX_PENDING_REQUESTS + JournalActivityCapture.MAX_PENDING_HANDOFFS);
    }

    @Test
    void durableQueueLossIsRecordedInLiveEvidenceCompleteness() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        writer.blockAppend = new CountDownLatch(1);
        DurableJournalCapture capture = capture(journal, writer, settings(2, 20_000, 1, 1, seconds(60), seconds(60)));

        journal.offer(event(10, "first"));
        journal.dispatchPending();
        assertThat(writer.appendStarted.await(5, TimeUnit.SECONDS)).isTrue();
        capture.onEntries(List.of(entry(2, 20, "second"), entry(3, 30, "third")));

        assertThat(capture.status().dropped()).isPositive();
        assertThat(journal.lossHorizonMillis()).isNotNull();
        writer.blockAppend.countDown();
    }

    @Test
    void aConflictingFullKeyIsQuarantinedWithoutPoisoningLaterRows() throws Exception {
        JdbcDataSource dataSource = dataSource();
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "capture");
        DurableJournalScope scope = scope();
        store.upsertRun(run(scope));
        store.append(scope, entry(1, 1, "already-there"));
        RuntimeJournal journal = journal();
        DurableJournalCapture capture = DurableJournalCapture.start(
                journal,
                new DurableJournalIdentity(scope.namespace(), scope.application(), scope.run(), "1234", "instance"),
                store,
                new JournalActivityFeed(0, 2, null),
                settings(64, 16_000, 64, 8, millis(10), seconds(60)));
        captures.add(capture);

        journal.offer(event(10, "conflict"));
        journal.offer(event(20, "valid"));
        journal.dispatchPending();

        await(() -> capture.status().durableWatermark() == 2);
        await(() -> capture.status().quarantined() == 1);
        await(() -> store.findRun(scope).checkpoint() == 2);
        assertThat(store.query(scope).entries())
                .extracting(entry -> entry.event().requestId())
                .containsExactly("already-there", "valid");
        assertThat(store.findRun(scope).checkpoint()).isEqualTo(2);
    }

    @Test
    void captureCloseMakesABoundedFinalFlushAndMarksTheRunStopped() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalCapture capture = capture(journal, writer, settings(64, 16_000, 64, 8, seconds(60), seconds(60)));
        journal.offer(event(10, "last"));
        journal.dispatchPending();

        capture.close();

        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L);
        assertThat(capture.status().state()).isEqualTo("CLOSED");
        assertThat(capture.status().finalFlushCompleted()).isTrue();
        assertThat(writer.runs).last().extracting(DurableJournalRun::status).isEqualTo("STOPPED");
        assertThat(writer.checkpoints).contains(1L);
    }

    @Test
    void aClosedCaptureIsNotNotifiedByLaterJournalBatches() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalCapture capture = capture(journal, writer, settings(64, 16_000, 1, 8, millis(5), seconds(60)));
        capture.close();
        long accepted = capture.status().accepted();

        journal.offer(event(10, "after-close"));
        journal.dispatchPending();
        await(() -> writer.entries.isEmpty());

        assertThat(capture.status().accepted()).isEqualTo(accepted);
        assertThat(journal.notifies(capture)).isFalse();
    }

    @Test
    void durableWatermarkNeverMovesBackwardsAcrossRetriesAndConflicts() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        writer.failAppends.set(1);
        DurableJournalCapture capture = capture(journal, writer, settings(64, 16_000, 1, 1, millis(5), seconds(60)));
        List<Long> observed = new CopyOnWriteArrayList<>();

        for (int sequence = 0; sequence < 8; sequence++) {
            journal.offer(event(sequence, "request-" + sequence));
            journal.dispatchPending();
            observed.add(capture.status().durableWatermark());
        }
        await(() -> capture.status().durableWatermark() == 8);
        observed.add(capture.status().durableWatermark());

        assertThat(observed).isSorted();
        assertThat(capture.status().durableWatermark()).isEqualTo(8);
    }

    @Test
    void capturesMaskedDetailsOnlyAfterTheOwningEventAppendIsAcknowledged() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalIdentity identity = new DurableJournalIdentity("local", "orders", "run-1", "1234", "instance");
        DurableJournalCapture capture = DurableJournalCapture.start(
                journal,
                identity,
                writer,
                new JournalActivityFeed(0, 2, null),
                settings(64, 16_000, 64, 8, seconds(60), seconds(60)),
                List.of(new HttpPayloadDurableJournalDetailProvider()));
        captures.add(capture);
        capture.onEntries(List.of(entry(
                1,
                new RuntimeEvent(
                        JournalSource.HTTP,
                        10,
                        1,
                        "request-1",
                        "execution",
                        "trace",
                        "worker",
                        ThreadKind.WORKER,
                        false,
                        new HttpPayload("GET", "/orders/42?token=raw", "/orders/{id}", "readOrders token=raw", 200)))));
        capture.flushNow();

        await(() -> capture.status().durableWatermark() == 1 && !writer.details.isEmpty());

        assertThat(writer.operations).containsExactly("append", "detail", "checkpoint");
        assertThat(writer.details).singleElement().satisfies(detail -> {
            assertThat(detail.journalSequence()).isEqualTo(1);
            assertThat(detail.routeTemplate()).isEqualTo("/orders/{id}");
            assertThat(detail.operation()).doesNotContain("raw");
            assertThat(detail.toString()).doesNotContain("/orders/42", "token=raw");
        });
    }

    @Test
    void detailFailureIsWarningVisibleWithoutRepeatingTheAcknowledgedAppend() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        writer.failDetails.set(1);
        DurableJournalIdentity identity = new DurableJournalIdentity("local", "orders", "run-1", "1234", "instance");
        DurableJournalCapture capture = DurableJournalCapture.start(
                journal,
                identity,
                writer,
                new JournalActivityFeed(0, 2, null),
                settings(64, 16_000, 64, 8, seconds(60), seconds(60)),
                List.of((scope, entries) -> List.of(
                        DurableJournalDetail.request(scope, entries.get(0).sequence(), "GET", "/a", null, 200))));
        captures.add(capture);

        capture.onEntries(List.of(entry(1, 10, "request-1")));
        capture.flushNow();
        await(() -> capture.status().durableWatermark() == 1 && !writer.checkpoints.isEmpty());

        assertThat(writer.attempts).hasValue(1);
        assertThat(writer.detailAttempts).hasValue(1);
        assertThat(writer.checkpoints).containsExactly(1L);
        assertThat(capture.status().pending()).isZero();
        assertThat(capture.status().lastError())
                .contains("cannot persist detail", "owning event append is already durable", "database unavailable");
    }

    @Test
    void clearWaitsBehindAnInFlightAppendDropsPendingOldRowsAndPreservesFutureRows() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        writer.blockAppend = new CountDownLatch(1);
        DurableJournalCapture capture = capture(journal, writer, settings(64, 16_000, 64, 8, seconds(60), seconds(60)));
        journal.offer(event(10, "old"));
        journal.dispatchPending();
        capture.flushNow();
        assertThat(writer.appendStarted.await(5, TimeUnit.SECONDS)).isTrue();

        journal.clear();
        assertThat(capture.status().pending()).isZero();
        assertThat(capture.status().durableWatermark()).isEqualTo(1);
        journal.offer(event(20, "future"));
        journal.dispatchPending();

        writer.blockAppend.countDown();
        await(() -> writer.entries.stream().map(JournalEntry::sequence).toList().equals(List.of(2L)));
        await(() -> capture.status().durableWatermark() == 2);

        assertThat(writer.clearAttempts).hasValue(1);
        assertThat(writer.operations.indexOf("clear")).isGreaterThan(writer.operations.indexOf("append"));
        assertThat(writer.operations.lastIndexOf("append")).isGreaterThan(writer.operations.indexOf("clear"));
        assertThat(writer.entries)
                .extracting(entry -> entry.event().requestId())
                .containsExactly("future");
        assertThat(writer.checkpoints).isSorted();
    }

    @Test
    void failedClearWarnsAndDoesNotResurrectAnInFlightRow() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        writer.entries.add(entry(1, 10, "durable-history"));
        writer.failAppends.set(1);
        writer.failClears.set(1);
        writer.blockAppend = new CountDownLatch(1);
        DurableJournalCapture capture = capture(journal, writer, settings(64, 16_000, 64, 8, seconds(60), seconds(60)));
        journal.offer(event(10, "old"));
        journal.dispatchPending();
        capture.flushNow();
        assertThat(writer.appendStarted.await(5, TimeUnit.SECONDS)).isTrue();

        journal.clear();
        writer.blockAppend.countDown();
        await(() -> writer.clearAttempts.get() == 1);
        await(() -> writer.checkpoints.contains(1L));
        await(() -> capture.status().lastError() != null
                && capture.status().lastError().contains("clear through sequence 1"));

        assertThat(writer.attempts).hasValue(1);
        assertThat(capture.status().pending()).isZero();
        assertThat(writer.entries)
                .extracting(entry -> entry.event().requestId())
                .containsExactly("durable-history");
        assertThat(writer.checkpoints).contains(1L);
    }

    @Test
    void durableSettingsSchedulePeriodicPartitionRetention() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalSettings durable = new DurableJournalSettings(
                "local",
                "orders",
                "run-1",
                "1234",
                "instance",
                true,
                millis(5),
                64,
                16_000,
                32,
                "bootui_activity",
                millis(1),
                false);
        DurableJournalCapture capture = DurableJournalCapture.start(
                journal,
                durable,
                writer,
                new JournalActivityFeed(0, 2, null),
                settings(64, 16_000, 64, 8, millis(5), seconds(60)));
        captures.add(capture);

        Thread.sleep(50);
        assertThat(writer.pruneAttempts).hasValue(0);
        capture.pruneNow();

        assertThat(writer.pruneScopes).isNotEmpty().allMatch(scope("local", "orders", "run-1")::equals);
    }

    @Test
    void heartbeatReArmsAFlushWhenAPendingQueueLostItsWakeup() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalCapture capture = capture(journal, writer, settings(64, 16_000, 64, 8, seconds(60), millis(5)));

        journal.offer(event(10, "pending"));
        journal.dispatchPending();

        await(() -> writer.entries.size() == 1);
        assertThat(capture.status().pending()).isZero();
    }

    @Test
    void heartbeatFailureDoesNotCancelFutureFlushWakeups() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalCapture capture = capture(journal, writer, settings(64, 16_000, 64, 8, seconds(60), millis(5)));
        await(() -> !writer.runs.isEmpty());
        writer.failRunErrors.set(1);

        journal.offer(event(10, "pending-after-heartbeat-error"));
        journal.dispatchPending();

        await(() -> writer.entries.size() == 1);
        assertThat(capture.status().pending()).isZero();
        assertThat(capture.status().lastError()).contains("heartbeat failure", "next heartbeat will retry");
    }

    @Test
    void metadataSnapshotFailureDoesNotBlockDurableEventWrites() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalSettings durable = new DurableJournalSettings(
                "local",
                "orders",
                "run-1",
                "1234",
                "instance",
                true,
                millis(5),
                64,
                16_000,
                32,
                "bootui_activity",
                seconds(60),
                false);
        DurableJournalCapture capture = DurableJournalCapture.start(
                journal,
                durable,
                writer,
                new JournalActivityFeed(0, 2, null),
                settings(64, 16_000, 1, 8, millis(5), seconds(60)),
                List.of(),
                phase -> {
                    throw new IllegalArgumentException("invalid metadata");
                });
        captures.add(capture);

        journal.offer(event(10, "still-durable"));
        journal.dispatchPending();

        await(() -> writer.entries.size() == 1);
        assertThat(capture.status().pending()).isZero();
        assertThat(capture.status().lastError()).contains("heartbeat continues without metadata");
    }

    @Test
    void journalCloseDrivesTheCaptureFinalFlush() {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalCapture capture = capture(journal, writer, settings(64, 16_000, 64, 8, seconds(60), seconds(60)));
        journal.offer(event(10, "last"));
        journal.dispatchPending();

        journal.close();

        assertThat(capture.status().state()).isEqualTo("CLOSED");
        assertThat(capture.status().finalFlushCompleted()).isTrue();
        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L);
    }

    private DurableJournalCapture capture(
            RuntimeJournal journal, DurableJournalWriter writer, DurableJournalCaptureSettings settings) {
        DurableJournalCapture capture = DurableJournalCapture.start(journal, scope(), writer, settings);
        captures.add(capture);
        return capture;
    }

    private RuntimeJournal journal() {
        return journal(128);
    }

    private RuntimeJournal journal(int capacity) {
        RuntimeJournalSettings settings = new RuntimeJournalSettings(
                true, capacity, 1_000_000, 256, 10, 10, java.util.Set.of(JournalSource.SQL, JournalSource.HTTP));
        RuntimeJournal journal = new RuntimeJournal(settings, RunIdentity.start(), false);
        journals.add(journal);
        return journal;
    }

    private static void retainFive(RuntimeJournal journal) {
        for (int sequence = 1; sequence <= 5; sequence++) {
            assertThat(journal.offer(event(sequence, "retained-" + sequence))).isTrue();
        }
        journal.dispatchPending();
    }

    private static DurableJournalCaptureSettings settings(
            int entries, long bytes, int threshold, int chunk, Duration interval, Duration heartbeat) {
        return new DurableJournalCaptureSettings(
                entries, bytes, threshold, bytes, chunk, interval, heartbeat, Duration.ofSeconds(2));
    }

    private static Duration seconds(int value) {
        return Duration.ofSeconds(value);
    }

    private static Duration millis(int value) {
        return Duration.ofMillis(value);
    }

    private static DurableJournalScope scope() {
        return new DurableJournalScope("local", "orders", RunIdentity.start().id());
    }

    private static DurableJournalScope scope(String namespace, String application, String run) {
        return new DurableJournalScope(namespace, application, run);
    }

    private static DurableJournalRun run(DurableJournalScope scope) {
        return new DurableJournalRun(scope, 1, 1, "RUNNING", null, 0);
    }

    private static RuntimeEvent event(long epochMillis, String requestId) {
        return new RuntimeEvent(
                JournalSource.SQL,
                epochMillis,
                1,
                requestId,
                "execution",
                "trace",
                "worker",
                ThreadKind.WORKER,
                false,
                new SqlPayload("select * from orders where id = ?", null, "primary", false));
    }

    private static RuntimeEvent completion(long epochMillis, String requestId) {
        return new RuntimeEvent(
                JournalSource.HTTP,
                epochMillis,
                1,
                requestId,
                null,
                "trace",
                "worker",
                ThreadKind.WORKER,
                false,
                new HttpPayload("GET", "/orders", "/orders", null, 200));
    }

    private static JournalEntry entry(long sequence, long epochMillis, String requestId) {
        return new JournalEntry(sequence, event(epochMillis, requestId), 128);
    }

    private static JournalEntry entry(long sequence, RuntimeEvent event) {
        return new JournalEntry(sequence, event, 128);
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:durable-capture-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class RecordingWriter implements DurableJournalWriter {

        private final CountDownLatch firstAcknowledgementEntered = new CountDownLatch(1);
        private volatile CountDownLatch blockAfterFirstAcknowledgement;
        private final List<JournalEntry> entries = new CopyOnWriteArrayList<>();
        private final List<DurableJournalEvent> events = new CopyOnWriteArrayList<>();
        private final List<DurableJournalRun> runs = new CopyOnWriteArrayList<>();
        private final List<DurableJournalScope> scopes = new CopyOnWriteArrayList<>();
        private final List<Long> checkpoints = new CopyOnWriteArrayList<>();
        private final List<DurableJournalDetail> details = new CopyOnWriteArrayList<>();
        private final List<String> operations = new CopyOnWriteArrayList<>();
        private final List<DurableJournalScope> pruneScopes = new CopyOnWriteArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger detailAttempts = new AtomicInteger();
        private final AtomicInteger clearAttempts = new AtomicInteger();
        private final AtomicInteger pruneAttempts = new AtomicInteger();
        private final AtomicInteger failAppends = new AtomicInteger();
        private final AtomicInteger failDetails = new AtomicInteger();
        private final AtomicInteger failClears = new AtomicInteger();
        private final AtomicInteger failRunErrors = new AtomicInteger();
        private final CountDownLatch appendStarted = new CountDownLatch(1);
        private volatile CountDownLatch blockAppend;

        @Override
        public DurableJournalRun upsertRun(DurableJournalRun run) {
            if (failRunErrors.getAndDecrement() > 0) {
                throw new AssertionError("simulated heartbeat failure");
            }
            if (run.checkpoint() == 1) {
                firstAcknowledgementEntered.countDown();
                waitFor(blockAfterFirstAcknowledgement);
            }
            runs.add(run);
            return run;
        }

        @Override
        public int appendEvents(DurableJournalScope scope, List<DurableJournalEvent> events) {
            operations.add("append");
            scopes.add(scope);
            attempts.incrementAndGet();
            appendStarted.countDown();
            waitFor(blockAppend);
            if (failAppends.getAndDecrement() > 0) {
                throw new DurableJournalStoreException("database unavailable");
            }
            this.entries.addAll(events.stream().map(DurableJournalEvent::entry).toList());
            this.events.addAll(events);
            return events.size();
        }

        @Override
        public boolean upsertDetail(DurableJournalDetail detail) {
            operations.add("detail");
            detailAttempts.incrementAndGet();
            if (failDetails.getAndDecrement() > 0) {
                throw new DurableJournalStoreException("database unavailable");
            }
            details.add(detail);
            return true;
        }

        @Override
        public DurableJournalDeletionCounts clearRunThrough(DurableJournalScope scope, long sequence) {
            operations.add("clear");
            clearAttempts.incrementAndGet();
            if (failClears.getAndDecrement() > 0) {
                throw new DurableJournalStoreException("database unavailable");
            }
            List<JournalEntry> removed = entries.stream()
                    .filter(entry -> entry.sequence() <= sequence)
                    .toList();
            entries.removeAll(removed);
            details.removeIf(detail -> detail.scope().equals(scope) && detail.journalSequence() <= sequence);
            return new DurableJournalDeletionCounts(removed.size(), 0, 0);
        }

        @Override
        public DurableJournalDeletionCounts prune(DurableJournalScope scope, long cutoffEpochMillis) {
            pruneScopes.add(scope);
            pruneAttempts.incrementAndGet();
            return new DurableJournalDeletionCounts(0, 0, 0);
        }

        @Override
        public DurableJournalRun checkpoint(DurableJournalScope scope, long sequence) {
            operations.add("checkpoint");
            checkpoints.add(sequence);
            DurableJournalRun run =
                    new DurableJournalRun(scope, 1, System.currentTimeMillis(), "RUNNING", null, sequence, "instance");
            return run;
        }

        private static void waitFor(CountDownLatch blocker) {
            if (blocker != null) {
                try {
                    blocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new DurableJournalStoreException("interrupted", ex);
                }
            }
        }
    }

    @org.junit.jupiter.api.AfterEach
    void close() {
        captures.forEach(DurableJournalCapture::close);
        journals.forEach(RuntimeJournal::close);
    }
}
