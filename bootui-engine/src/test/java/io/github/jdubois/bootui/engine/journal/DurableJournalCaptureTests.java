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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

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
    void memoryOffloadDropsHotEvidenceButKeepsPersistedAndPendingWrites() throws Exception {
        RuntimeJournal journal = journal();
        RecordingWriter writer = new RecordingWriter();
        DurableJournalCapture capture = capture(journal, writer, settings(64, 16_000, 64, 8, seconds(60), seconds(60)));
        journal.offer(event(10, "persisted"));
        journal.dispatchPending();
        capture.flushNow();
        assertThat(writer.checkpointReached.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(writer.entries).extracting(JournalEntry::sequence).containsExactly(1L);

        journal.offer(event(20, "pending"));
        journal.dispatchPending();
        assertThat(capture.status().pending()).isEqualTo(1);
        journal.offer(event(30, "not-dispatched"));

        assertThat(journal.offloadRetainedData()).isEqualTo(3);
        assertThat(journal.entries()).isEmpty();
        assertThat(journal.status().queueDepth()).isZero();
        assertThat(journal.lossHorizonMillis()).isEqualTo(30L);

        journal.offer(event(40, "after-offload"));
        journal.dispatchPending();
        capture.close();

        assertThat(writer.clearAttempts)
                .as("offload must not schedule a durable clear")
                .hasValue(0);
        assertThat(capture.status().finalFlushCompleted()).isTrue();
        assertThat(writer.entries)
                .extracting(entry -> entry.event().requestId())
                .containsExactly("persisted", "pending", "after-offload");
        assertThat(capture.status().durableWatermark()).isEqualTo(3);
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

        journal.offer(event(15, "pending"));
        journal.dispatchPending();
        assertThat(capture.status().pending()).isEqualTo(2);

        journal.clear();
        assertThat(capture.status().pending()).isZero();
        assertThat(capture.status().durableWatermark()).isEqualTo(2);
        journal.offer(event(20, "future"));
        journal.dispatchPending();

        writer.blockAppend.countDown();
        await(() -> writer.entries.stream().map(JournalEntry::sequence).toList().equals(List.of(3L)));
        await(() -> capture.status().durableWatermark() == 3);

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
        RuntimeJournalSettings settings =
                new RuntimeJournalSettings(true, 128, 1_000_000, 256, 10, 10, java.util.Set.of(JournalSource.SQL));
        RuntimeJournal journal = new RuntimeJournal(settings, RunIdentity.start(), false);
        journals.add(journal);
        return journal;
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
        private final CountDownLatch checkpointReached = new CountDownLatch(1);
        private volatile CountDownLatch blockAppend;

        @Override
        public DurableJournalRun upsertRun(DurableJournalRun run) {
            if (failRunErrors.getAndDecrement() > 0) {
                throw new AssertionError("simulated heartbeat failure");
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
            CountDownLatch blocker = blockAppend;
            if (blocker != null) {
                try {
                    blocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new DurableJournalStoreException("interrupted", ex);
                }
            }
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
            checkpointReached.countDown();
            DurableJournalRun run =
                    new DurableJournalRun(scope, 1, System.currentTimeMillis(), "RUNNING", null, sequence, "instance");
            return run;
        }
    }

    @org.junit.jupiter.api.AfterEach
    void close() {
        captures.forEach(DurableJournalCapture::close);
        journals.forEach(RuntimeJournal::close);
    }
}
