package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.ConnectionsPerRequest;
import io.github.jdubois.bootui.engine.insights.GcInflatedLatency;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.RepeatedSelects;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DurableJournalEvidenceTests {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private final List<RuntimeJournal> journals = new java.util.ArrayList<>();

    @AfterEach
    void closeJournals() {
        journals.forEach(RuntimeJournal::close);
    }

    @Test
    void aHotJournalDoesNotQueryDurableStorageBeforeAnythingWasOffloaded() {
        RuntimeJournal journal = journal(100, 1_000_000);
        journal.offer(unownedSql(1));
        journal.dispatchPending();
        DurableJournalStore unavailable = new DurableJournalStore() {
            @Override
            public void verifySchema() {
                throw new AssertionError("not queried");
            }

            @Override
            public DurableJournalRun upsertRun(DurableJournalRun run) {
                throw new AssertionError("not queried");
            }

            @Override
            public DurableJournalRun checkpoint(DurableJournalScope scope, long sequence) {
                throw new AssertionError("not queried");
            }

            @Override
            public int appendEvents(DurableJournalScope scope, List<DurableJournalEvent> events) {
                throw new AssertionError("not queried");
            }

            @Override
            public DurableJournalRun findRun(DurableJournalScope scope) {
                throw new AssertionError("not queried");
            }

            @Override
            public DurableJournalRunMetadata findRunMetadata(DurableJournalScope scope) {
                throw new AssertionError("not queried");
            }

            @Override
            public List<DurableJournalRun> listRuns(String namespace, String application) {
                throw new AssertionError("not queried");
            }

            @Override
            public List<DurableJournalRun> listRuns(String namespace, String application, int limit) {
                throw new AssertionError("not queried");
            }

            @Override
            public int appendBatch(DurableJournalScope scope, List<JournalEntry> entries) {
                throw new AssertionError("not queried");
            }

            @Override
            public DurableJournalPage query(DurableJournalQuery query) {
                throw new AssertionError("not queried");
            }

            @Override
            public DurableJournalFeedPage queryFeed(DurableJournalFeedQuery query) {
                throw new AssertionError("not queried");
            }

            @Override
            public DurableJournalGlobalCursor globalCursor(DurableJournalScope scope, long journalSequence) {
                throw new AssertionError("not queried");
            }

            @Override
            public boolean upsertDetail(DurableJournalDetail detail) {
                throw new AssertionError("not queried");
            }

            @Override
            public List<DurableJournalDetail> listDetails(DurableJournalScope scope) {
                throw new AssertionError("not queried");
            }
        };

        JournalEvidenceSnapshot snapshot = new DurableJournalEvidence(unavailable).read(scope(journal), journal);

        assertThat(snapshot.entries()).extracting(JournalEntry::sequence).containsExactly(1L);
        assertThat(snapshot.omittedEvents()).isZero();
    }

    @Test
    void contiguousSequencesDoNotHideAnUndispatchedDiscardDuringLaterDispatch() throws Exception {
        RuntimeJournal journal = journal(100, 1_000_000);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        journal.offer(requestHttp("request-1", 1_100));
        journal.dispatchPending();
        DurableJournalIdentity identity = new DurableJournalIdentity(
                scope.namespace(),
                scope.application(),
                scope.run(),
                "process",
                journal.status().instanceId());
        DurableJournalRunMetadata metadata = DurableJournalRunMetadata.snapshot(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                journal,
                identity,
                InsightsStack.SPRING_MVC,
                Map.of(),
                null,
                null);
        store.upsertRun(new DurableJournalRun(scope, 1, 1, "RUNNING", null, 1, identity.instanceId(), metadata));
        persist(store, scope, journal.entries());

        journal.offer(requestSql("request-1", 1_200));
        assertThat(journal.offloadRetainedData()).isEqualTo(2);

        CountDownLatch inListener = new CountDownLatch(1);
        CountDownLatch finishDispatch = new CountDownLatch(1);
        journal.addListener(entries -> {
            inListener.countDown();
            try {
                assertThat(finishDispatch.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        });
        assertThat(journal.offer(requestHttp("request-2", 1_300))).isTrue();
        CompletableFuture<Void> dispatch = CompletableFuture.runAsync(journal::dispatchPending);
        try {
            assertThat(inListener.await(5, TimeUnit.SECONDS)).isTrue();
            JournalEvidenceSnapshot snapshot =
                    new DurableJournalEvidence(store).read(scope, journal, new DurableJournalCapture.ReadWindow(0));
            assertThat(snapshot.entries()).extracting(JournalEntry::sequence).containsExactly(2L, 1L);
            assertThat(snapshot.omittedEvents()).isZero();
            assertThat(snapshot.lossHorizonMillis()).isEqualTo(1_200L);
        } finally {
            finishDispatch.countDown();
            dispatch.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anActiveRequestIsReconstructedFromDurableAndUnacknowledgedHotEvidence() {
        RuntimeJournal journal = journal(100, 1_000_000);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        store.upsertRun(run(scope, 4, journal.settings()));
        journal.addListener(entries -> persist(store, scope, entries));
        journal.offer(unownedSql(1));
        journal.dispatchPending();
        assertThat(journal.offloadDurableThrough(1, 0)).isEqualTo(1);

        journal.offer(requestSql("request-1", 1_100));
        journal.dispatchPending();
        assertThat(journal.offloadDurableThrough(2, 0)).isZero();
        journal.offer(requestHttp("request-1", 1_200));
        journal.dispatchPending();

        JournalEvidenceSnapshot snapshot = new DurableJournalEvidence(store).read(scope, journal);
        RequestJournalProfileDto profile =
                new RequestJournalProfiles(journal, null, 0, 2, null).profileFrom(snapshot, "request-1");

        assertThat(snapshot.entries()).extracting(JournalEntry::sequence).containsExactly(3L, 2L, 1L);
        assertThat(profile.available()).isTrue();
        assertThat(profile.timeline()).extracting(item -> item.source()).contains("sql");
        assertThat(snapshot.status().evictedByCount()).isZero();
        assertThat(snapshot.lossHorizonMillis()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void currentReadNeverReturnsArchiveEvidenceAcrossAClearBlockedBeforeTheWriter(boolean readStartsFirst)
            throws Exception {
        RuntimeJournal journal = journal(100, 1_000_000);
        DurableJournalScope scope = scope(journal);
        JdbcDurableJournalStore store = spy(store());
        CountDownLatch checkpointed = new CountDownLatch(1);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finishRead = new CountDownLatch(readStartsFirst ? 1 : 0);
        CountDownLatch clearing = new CountDownLatch(1);
        CountDownLatch finishClear = new CountDownLatch(1);
        doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    checkpointed.countDown();
                    return result;
                })
                .when(store)
                .checkpoint(any(), anyLong());
        doAnswer(invocation -> {
                    reading.countDown();
                    assertThat(finishRead.await(10, TimeUnit.SECONDS)).isTrue();
                    return invocation.callRealMethod();
                })
                .when(store)
                .query(any(DurableJournalQuery.class));
        journal.addListener(new JournalListener() {
            @Override
            public void onEntries(List<JournalEntry> entries) {}

            @Override
            public void onClear() {
                clearing.countDown();
                try {
                    assertThat(finishClear.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }
        });
        var settings = new DurableJournalCaptureSettings(
                64, 16_000, 64, 16_000, 8, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(2));
        try (DurableJournalCapture capture = DurableJournalCapture.start(journal, scope, store, settings)) {
            DurableJournalRuntime runtime = new DurableJournalRuntime();
            assertThat(runtime.activate(store, capture)).isTrue();
            assertThat(journal.offer(requestHttp("request-1", 1_100))).isTrue();
            journal.dispatchPending();
            capture.flushNow();
            assertThat(checkpointed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(journal.offloadRetainedData()).isEqualTo(1);

            CompletableFuture<JournalEvidenceSnapshot> read = readStartsFirst
                    ? CompletableFuture.supplyAsync(() -> runtime.readCurrentEvidence(journal, scope))
                    : null;
            CompletableFuture<Void> clear = null;
            try {
                if (readStartsFirst) {
                    assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                }
                clear = CompletableFuture.runAsync(journal::clear);
                assertThat(clearing.await(5, TimeUnit.SECONDS)).isTrue();
                finishRead.countDown();
                if (read == null) {
                    read = CompletableFuture.supplyAsync(() -> runtime.readCurrentEvidence(journal, scope));
                }
                assertThat(read.get(5, TimeUnit.SECONDS)).isNull();
                if (!readStartsFirst) {
                    verify(store, never()).query(any(DurableJournalQuery.class));
                }
            } finally {
                finishRead.countDown();
                finishClear.countDown();
                if (clear != null) {
                    clear.get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void historicalTypedPayloadsDriveInsightsAndARequestProfileAfterOffload() {
        RuntimeJournal journal = journal(100, 10_000_000);
        journal.registerSqlCapture("orders", SqlPayload.Provenance.EXECUTION);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        store.upsertRun(run(scope, 10, journal.settings()));
        journal.addListener(entries -> persist(store, scope, entries));
        for (int i = 0; i < 5; i++) {
            journal.offer(requestSql("request-1", 1_010 + i));
        }
        journal.offer(requestConnection("request-1", 1_040));
        journal.offer(gc(1_050));
        journal.offer(requestHttp("request-1", 1_100));
        journal.offer(requestHttp("request-2", 1_200));
        journal.dispatchPending();
        assertThat(journal.offloadDurableThrough(journal.lastSequence(), 0)).isEqualTo(9);

        JournalEvidenceSnapshot snapshot = new DurableJournalEvidence(store).read(scope, journal);
        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).reportFrom(snapshot);
        RequestJournalProfileDto profile =
                new RequestJournalProfiles(journal, null, 0, 2, null).profileFrom(snapshot, "request-1");

        assertThat(snapshot.entries()).anyMatch(entry -> entry.event().payload() instanceof SqlPayload);
        assertThat(snapshot.entries()).anyMatch(entry -> entry.event().payload() instanceof ConnectionPayload);
        assertThat(snapshot.entries()).anyMatch(entry -> entry.event().payload() instanceof GcPayload);
        assertThat(snapshot.entries()).anyMatch(entry -> entry.event().payload() instanceof HttpPayload);
        assertThat(report.available()).isTrue();
        assertThat(report.window().retainedEvents()).isEqualTo(9);
        assertThat(report.checks())
                .extracting(check -> check.kind())
                .contains(RepeatedSelects.KIND, ConnectionsPerRequest.KIND, GcInflatedLatency.KIND);
        assertThat(profile.available()).isTrue();
        assertThat(profile.timeline()).extracting(item -> item.source()).contains("sql", "connection");
        assertThat(profile.gcPauses()).hasSize(1);
        assertThat(report.checks())
                .filteredOn(check -> GcInflatedLatency.KIND.equals(check.kind()))
                .singleElement()
                .extracting(check -> check.eligibleRequests())
                .isEqualTo(1L);
        assertThat(snapshot.status().evictedByCount() + snapshot.status().evictedByBytes())
                .isZero();
        assertThat(snapshot.lossHorizonMillis()).isNull();
    }

    @Test
    void historicalExceptionIdentityAndSafeDetailSurviveTheEvidenceRead() {
        RuntimeJournal journal = journal(100, 10_000_000);
        journal.offer(RuntimeEvent.of(
                JournalSource.EXCEPTION,
                1_000,
                1_000,
                CorrelationContext.forRequest("request-with-exception").withTrace("trace-1", "span-1"),
                "worker",
                ThreadKind.WORKER,
                false,
                new ExceptionPayload(null, "java.lang.IllegalStateException", null, List.of())));
        journal.offer(requestHttp("request-with-exception", 1_100));
        journal.dispatchPending();
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        JournalEvidenceSnapshot hot = JournalEvidenceSnapshot.hot(journal);
        DurableJournalIdentity identity =
                new DurableJournalIdentity(scope.namespace(), scope.application(), scope.run(), "process", "instance");
        DurableJournalRunMetadata metadata = DurableJournalRunMetadata.snapshot(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                journal,
                identity,
                InsightsStack.SPRING_MVC,
                Map.of(),
                null,
                null);
        store.upsertRun(
                new DurableJournalRun(scope, 1, 1, "STOPPED", null, journal.lastSequence(), "instance", metadata));
        store.appendBatch(scope, hot.entries());
        long exceptionSequence = hot.entries().stream()
                .filter(entry -> entry.event().payload() instanceof ExceptionPayload)
                .mapToLong(JournalEntry::sequence)
                .findFirst()
                .orElseThrow();
        store.upsertDetail(DurableJournalDetail.exception(
                scope,
                exceptionSequence,
                "exception-group",
                "java.lang.IllegalStateException",
                "signature",
                List.of("java.lang.IllegalStateException"),
                List.of("Example.run(Example.java:1)"),
                List.of("java.lang.IllegalArgumentException"),
                1));

        JournalEvidenceSnapshot evidence = new DurableJournalEvidence(store).read(scope);

        JournalEntry exception = evidence.entries().stream()
                .filter(entry -> entry.event().payload() instanceof ExceptionPayload)
                .findFirst()
                .orElseThrow();
        ExceptionPayload payload = (ExceptionPayload) exception.event().payload();
        assertThat(exception.event().requestId()).isEqualTo("request-with-exception");
        assertThat(exception.event().traceId()).isEqualTo("trace-1");
        assertThat(payload.groupId()).isEqualTo("exception-group");
        assertThat(payload.types()).contains("java.lang.IllegalStateException", "java.lang.IllegalArgumentException");
        assertThat(new RequestJournalProfiles(null, null, 0, 2, null)
                        .profileFrom(evidence, "request-with-exception")
                        .available())
                .isTrue();
    }

    @Test
    void metadataSnapshotsKeepOnlyTheNewestBoundedLossIdentifiers() {
        RuntimeJournal journal = journal(1, 1_000_000);
        for (int index = 1; index <= DurableJournalRunMetadata.MAX_LOST_REQUEST_TRACES + 80; index++) {
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000 + index,
                    1_000,
                    CorrelationContext.forRequest("request-" + index).withTrace("trace-" + index, "span"),
                    "http-1",
                    ThreadKind.WORKER,
                    false,
                    new HttpPayload("GET", "/orders/" + index, "/orders/{id}", null, 200)));
            journal.dispatchPending();
        }

        DurableJournalRunMetadata metadata = DurableJournalRunMetadata.snapshot(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                journal,
                new DurableJournalIdentity("local", "orders", journal.run().id(), "process", "instance"),
                InsightsStack.SPRING_MVC,
                Map.of(),
                null,
                null);

        assertThat(metadata.lostRequestTraces()).hasSize(DurableJournalRunMetadata.MAX_LOST_REQUEST_TRACES);
        assertThat(metadata.lostRequestTraces()).contains("trace-1103").doesNotContain("trace-1");
    }

    @Test
    void metadataSnapshotsDropIdentifiersThatCannotBeSafelyEncoded() {
        RuntimeJournal journal = journal(1, 1_000_000);
        journal.offer(new RuntimeEvent(
                JournalSource.SQL,
                1_000,
                1,
                "request-1",
                null,
                "trace-1",
                "t".repeat(DurableJournalRunMetadata.MAX_LOST_VALUE_BYTES + 1),
                ThreadKind.WORKER,
                false,
                new SqlPayload("select 1", null, "orders", false)));
        journal.offer(requestHttp("request-1", 1_100));
        journal.dispatchPending();

        DurableJournalRunMetadata metadata = DurableJournalRunMetadata.snapshot(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                journal,
                new DurableJournalIdentity("local", "orders", journal.run().id(), "process", "instance"),
                InsightsStack.SPRING_MVC,
                Map.of(),
                null,
                null);

        assertThat(metadata.lostRequestThreads()).doesNotContain("t".repeat(513));
    }

    @Test
    void captureLossMakesHistoricalInsightsAndProfilesPartial() {
        RuntimeJournal journal = journal(100, 10_000_000);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        journal.offer(requestHttp("request-1", 1_100));
        journal.dispatchPending();
        DurableJournalRunMetadata metadata = DurableJournalRunMetadata.snapshot(
                        DurableJournalRunMetadata.Phase.CHECKPOINT,
                        journal,
                        new DurableJournalIdentity(
                                scope.namespace(), scope.application(), scope.run(), "process", "instance"),
                        InsightsStack.SPRING_MVC,
                        Map.of(),
                        null,
                        null)
                .withCaptureLoss(1, 0, 0);
        store.upsertRun(new DurableJournalRun(scope, 1, 1, "RUNNING", null, 1, "instance", metadata));
        store.appendBatch(scope, journal.entries());

        JournalEvidenceSnapshot snapshot = new DurableJournalEvidence(store).read(scope);
        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(null, null, null, InsightsStack.SPRING_MVC, null).reportFrom(snapshot);
        RequestJournalProfileDto profile =
                new RequestJournalProfiles(null, null, 0, 2, null).profileFrom(snapshot, "request-1");

        assertThat(snapshot.partial()).isTrue();
        assertThat(snapshot.durableCaptureLoss()).isEqualTo(1);
        assertThat(report.limitations()).anyMatch(reason -> reason.contains("durable capture lost 1 entry"));
        assertThat(profile.notes()).anyMatch(reason -> reason.contains("durable capture lost 1 entry"));
    }

    @Test
    void theDurableWindowIsBoundedAndReportsOmittedEntriesAsPartial() {
        RuntimeJournal journal = journal(2, 10_000_000);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        RuntimeJournalSettings settings = journal.settings();
        DurableJournalRunMetadata metadata = metadata(settings, 4);
        store.upsertRun(new DurableJournalRun(scope, 1, 1, "STOPPED", null, 4, "instance", metadata));
        List<JournalEntry> entries = List.of(
                new JournalEntry(1, unownedSql(1), unownedSql(1).estimatedBytes()),
                new JournalEntry(2, unownedSql(2), unownedSql(2).estimatedBytes()),
                new JournalEntry(3, unownedSql(3), unownedSql(3).estimatedBytes()),
                new JournalEntry(4, unownedSql(4), unownedSql(4).estimatedBytes()));
        store.appendBatch(scope, entries);

        JournalEvidenceSnapshot snapshot = new DurableJournalEvidence(store).read(scope);

        assertThat(snapshot.entries()).extracting(JournalEntry::sequence).containsExactly(4L, 3L);
        assertThat(snapshot.status().retainedEvents()).isEqualTo(2);
        assertThat(snapshot.status().maxEvents()).isEqualTo(2);
        assertThat(snapshot.omittedEvents()).isEqualTo(2);
        assertThat(snapshot.partial()).isTrue();
        assertThat(snapshot.status().evictedByCount() + snapshot.status().evictedByBytes())
                .isZero();
        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(null, null, null, InsightsStack.SPRING_MVC, null).reportFrom(snapshot);
        assertThat(report.limitations()).anyMatch(reason -> reason.contains("omitted 2 entries"));
    }

    @Test
    void missingTrailingDurableSequencesAreReportedAsPartial() {
        RuntimeJournal journal = journal(100, 10_000_000);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        store.upsertRun(
                new DurableJournalRun(scope, 1, 1, "RUNNING", null, 3, "instance", metadata(journal.settings(), 3)));
        store.appendBatch(
                scope, List.of(new JournalEntry(1, unownedSql(1), 128), new JournalEntry(2, unownedSql(2), 128)));

        JournalEvidenceSnapshot snapshot = new DurableJournalEvidence(store).read(scope);

        assertThat(snapshot.partial()).isTrue();
        assertThat(snapshot.omittedEvents()).isEqualTo(1);
    }

    @Test
    void theDurableWindowAlsoHonorsTheRuntimeByteBound() {
        int twoEvents = unownedSql(1).estimatedBytes() * 2 + 1;
        RuntimeJournal journal = journal(100, twoEvents);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        store.upsertRun(
                new DurableJournalRun(scope, 1, 1, "STOPPED", null, 4, "instance", metadata(journal.settings(), 4)));
        List<JournalEntry> entries = List.of(
                new JournalEntry(1, unownedSql(1), unownedSql(1).estimatedBytes()),
                new JournalEntry(2, unownedSql(2), unownedSql(2).estimatedBytes()),
                new JournalEntry(3, unownedSql(3), unownedSql(3).estimatedBytes()),
                new JournalEntry(4, unownedSql(4), unownedSql(4).estimatedBytes()));
        store.appendBatch(scope, entries);

        JournalEvidenceSnapshot snapshot = new DurableJournalEvidence(store).read(scope);

        assertThat(snapshot.entries()).hasSizeLessThanOrEqualTo(2);
        assertThat(snapshot.status().retainedBytes()).isLessThanOrEqualTo(twoEvents);
        assertThat(snapshot.omittedEvents()).isGreaterThanOrEqualTo(2);
        assertThat(snapshot.partial()).isTrue();
    }

    private RuntimeJournal journal(int maxEvents, long maxBytes) {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, maxEvents, maxBytes, 100, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false);
        journals.add(journal);
        return journal;
    }

    private static JdbcDurableJournalStore store() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:durable-evidence-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, "journal");
        store.verifySchema();
        return store;
    }

    private static DurableJournalScope scope(RuntimeJournal journal) {
        return new DurableJournalScope("local", "orders", journal.run().id());
    }

    private static DurableJournalRun run(DurableJournalScope scope, int checkpoint, RuntimeJournalSettings settings) {
        return new DurableJournalRun(scope, 1, 1, "RUNNING", null, checkpoint, null);
    }

    private static void persist(JdbcDurableJournalStore store, DurableJournalScope scope, List<JournalEntry> entries) {
        store.appendBatch(
                scope,
                entries.stream()
                        .sorted(Comparator.comparingLong(JournalEntry::sequence))
                        .toList());
    }

    private static DurableJournalRunMetadata metadata(RuntimeJournalSettings settings, int sequence) {
        JournalStatus status = new JournalStatus(
                true,
                "instance",
                "historical-run",
                sequence,
                0,
                0,
                0,
                0,
                settings.maxEvents(),
                settings.maxBytes(),
                0,
                0,
                0,
                0,
                null,
                null,
                0,
                settings.queueCapacity(),
                Map.of(),
                Map.of(),
                0,
                0);
        return new DurableJournalRunMetadata(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                1,
                "instance",
                "process",
                InsightsStack.SPRING_MVC,
                settings,
                status,
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                null,
                Map.of(),
                null,
                null);
    }

    private static RuntimeEvent unownedSql(long sequence) {
        return new RuntimeEvent(
                JournalSource.SQL,
                1_000 + sequence,
                1_000,
                null,
                null,
                null,
                "worker",
                ThreadKind.WORKER,
                false,
                new SqlPayload("select " + sequence, "Repo.find", "orders", false));
    }

    private static RuntimeEvent requestSql(String requestId, long start) {
        return RuntimeEvent.of(
                JournalSource.SQL,
                start,
                1_000,
                CorrelationContext.forRequest(requestId),
                "worker",
                ThreadKind.WORKER,
                false,
                new SqlPayload("select * from orders where id = 42", "Repo.find", "orders", false));
    }

    private static RuntimeEvent requestConnection(String requestId, long start) {
        return RuntimeEvent.of(
                JournalSource.CONNECTION,
                start,
                10_000,
                CorrelationContext.forRequest(requestId),
                "worker",
                ThreadKind.WORKER,
                false,
                new ConnectionPayload("orders", 5_000, 5, start));
    }

    private static RuntimeEvent gc(long start) {
        return new RuntimeEvent(
                JournalSource.GC,
                start,
                2_000_000,
                null,
                null,
                null,
                "GC",
                null,
                false,
                new GcPayload("G1 Young", 7, "end minor", "evacuation", true, 1_000, 500));
    }

    private static RuntimeEvent requestHttp(String requestId, long start) {
        return RuntimeEvent.of(
                JournalSource.HTTP,
                start,
                1_000_000,
                CorrelationContext.forRequest(requestId),
                "http-1",
                ThreadKind.WORKER,
                false,
                new HttpPayload(
                        "GET",
                        "/orders/42",
                        "/orders/{id}",
                        null,
                        200,
                        new ResourceUsage(
                                1_000, 100, 1, 0, null, 1, List.of(new GcPauseRange("G1 Young", 6, 7)), false)));
    }
}
