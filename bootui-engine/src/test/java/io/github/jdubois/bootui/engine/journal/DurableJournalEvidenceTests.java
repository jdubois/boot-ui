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
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DurableJournalEvidenceTests {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private final List<RuntimeJournal> journals = new java.util.ArrayList<>();

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "true, true"})
    void currentProfilesAndInsightsKeepTheLossOfWorkClearedBeforeTheRequestCompleted(
            boolean clear, boolean offloadBeforeClear) throws Exception {
        var journal = journal(100, 1_000_000);
        var store = store();
        var scope = scope(journal);
        var runtime = new DurableJournalRuntime();
        var settings = new DurableJournalCaptureSettings(
                64, 16_000, 64, 16_000, 8, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(2));
        try (var capture = captureWithMetadata(journal, durableIdentity(journal, scope), store, settings)) {
            assertThat(runtime.activate(store, capture)).isTrue();
            assertThat(journal.offer(requestSql("crossing", 2_010))).isTrue();
            journal.dispatchPending();
            flushThrough(store, scope, capture, 1);
            if (offloadBeforeClear) {
                assertThat(journal.offloadRetainedData()).isOne();
            }
            if (clear) {
                journal.clear();
            }

            assertThat(journal.offer(requestHttp("crossing", 2_000))).isTrue();
            assertThat(journal.offer(requestHttp("fresh", 3_000))).isTrue();
            journal.dispatchPending();
            flushThrough(store, scope, capture, 3);
            journal.offloadRetainedData();

            var evidence = runtime.readCurrentEvidence(journal, scope);
            assertThat(evidence).isNotNull();
            assertThat(evidence.entries())
                    .extracting(JournalEntry::sequence)
                    .containsExactlyElementsOf(clear ? List.of(3L, 2L) : List.of(3L, 2L, 1L));
            assertThat(evidence.omittedEvents()).isZero();
            assertThat(evidence.lossHorizonMillis()).isEqualTo(clear ? 2_010L : null);
            var report = new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                    .reportFrom(evidence);
            assertThat(report.window().requests()).isEqualTo(clear ? 1 : 2);

            var profiles = new RequestJournalProfiles(journal, null, 0, 2, null);
            var crossing = profiles.profileFrom(evidence, "crossing", "fresh");
            assertThat(crossing.available()).isTrue();
            assertThat(crossing.baselineComparison() == null).isEqualTo(clear);
            assertThat(crossing.timeline().stream()
                            .anyMatch(item -> item.source().equals("sql")))
                    .isEqualTo(!clear);
            var fresh = profiles.profileFrom(evidence, "fresh", "crossing");
            assertThat(fresh.available()).isTrue();
            assertThat(fresh.baselineComparison() == null).isEqualTo(clear);
            if (clear) {
                assertThat(crossing.notes()).anyMatch(note -> note.contains("loss horizon"));
                assertThat(fresh.notes()).anyMatch(note -> note.contains("crossing") && note.contains("incomplete"));
            }
        }
    }

    private static void flushThrough(
            DurableJournalStore store, DurableJournalScope scope, DurableJournalCapture capture, long sequence)
            throws InterruptedException {
        capture.flushNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var metadata = store.findRunMetadata(scope);
            if (metadata != null
                    && metadata.journalStatus().lastSequence() == sequence
                    && store.findRun(scope).checkpoint() == sequence) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError("The fixture did not checkpoint sequence " + sequence);
    }

    @Test
    void zeroSequenceOffloadKeepsKnownTraceAmbiguityInTheCurrentHotFallback() {
        var journal = journal(100, 1_000_000);
        var store = store();
        var scope = scope(journal);
        var settings = new DurableJournalCaptureSettings(
                64, 16_000, 64, 16_000, 8, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(2));
        try (var capture = captureWithMetadata(journal, durableIdentity(journal, scope), store, settings)) {
            var runtime = new DurableJournalRuntime();
            assertThat(runtime.activate(store, capture)).isTrue();
            assertThat(journal.offer(tracedHttp("lost", 1_000, "shared"))).isTrue();
            assertThat(journal.offloadRetainedData()).isOne();
            assertThat(journal.lastSequence()).isZero();
            assertThat(journal.offer(tracedHttp("later", 2_000, "shared"))).isTrue();
            assertThat(journal.offer(tracedAi(2_000, "shared"))).isTrue();
            journal.dispatchPending();
            assertThat(runtime.readCurrentEvidence(journal, scope)).isNull();
            assertThat(journal.evictedARequestOf("shared", true)).isTrue();
            var profile = new RequestJournalProfiles(journal, null, 0, 2, null).profile("later");
            assertThat(profile.available()).isTrue();
            assertThat(profile.timeline())
                    .as("hot fallback must not guess an owner when this recording lost one")
                    .noneMatch(item -> item.source().equals("ai"));
            journal.clear();
            assertThat(journal.offer(tracedHttp("next-recording", 3_000, "shared")))
                    .isTrue();
            assertThat(journal.offer(tracedAi(3_000, "shared"))).isTrue();
            journal.dispatchPending();
            assertThat(new RequestJournalProfiles(journal, null, 0, 2, null)
                            .profile("next-recording")
                            .timeline())
                    .anyMatch(item -> item.source().equals("ai"));
        }
    }

    @Test
    void aWriterConflictStillVetoesTheLiveTraceWhileItsMetadataIsStale() throws Exception {
        var journal = journal(100, 1_000_000);
        var store = spy(store());
        var scope = scope(journal);
        var identity = durableIdentity(journal, scope);
        store.upsertRun(new DurableJournalRun(
                scope,
                1,
                1,
                "RUNNING",
                null,
                1,
                identity.instanceId(),
                runMetadata(journal, identity, DurableJournalRunMetadata.Phase.CHECKPOINT)));
        persist(store, scope, List.of(new JournalEntry(1, requestHttp("different-row", 900), 128)));
        CountDownLatch refreshFailed = new CountDownLatch(1);
        doAnswer(invocation -> {
                    DurableJournalRun run = invocation.getArgument(0);
                    if (run.checkpoint() > 0) {
                        refreshFailed.countDown();
                        throw new DurableJournalStoreException("Synthetic failed metadata refresh");
                    }
                    return invocation.callRealMethod();
                })
                .when(store)
                .upsertRun(any());
        var settings = new DurableJournalCaptureSettings(
                64, 16_000, 64, 16_000, 8, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(2));
        try (var capture = captureWithMetadata(journal, identity, store, settings)) {
            var runtime = new DurableJournalRuntime();
            assertThat(runtime.activate(store, capture)).isTrue();
            assertThat(journal.offer(tracedHttp("conflicting-request", 1_000, "shared")))
                    .isTrue();
            assertThat(journal.offer(tracedHttp("later", 2_000, "shared"))).isTrue();
            assertThat(journal.offer(tracedAi(2_000, "shared"))).isTrue();
            journal.dispatchPending();
            capture.flushNow();
            assertThat(refreshFailed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(capture.status().quarantined()).isOne();
            assertThat(store.findRunMetadata(scope).durableQuarantinedEvents()).isZero();
            assertThat(store.findRunMetadata(scope).offloadTraceLoss().isEmpty())
                    .isTrue();
            assertThat(journal.offloadRetainedData()).isEqualTo(3);

            var snapshot = runtime.readCurrentEvidence(journal, scope);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.entries()).extracting(JournalEntry::sequence).containsExactly(3L, 2L, 1L);
            assertThat(snapshot.omittedEvents()).isZero();
            assertThat(snapshot.durableCaptureLoss()).isOne();
            assertThat(snapshot.complete()).isFalse();
            assertThat(journal.evictedARequestOf("shared", true)).isTrue();
            var profile = new RequestJournalProfiles(journal, null, 0, 2, null).profileFrom(snapshot, "later");
            assertThat(profile.timeline())
                    .as("contiguous sequences plus stale metadata must not lift a known writer veto")
                    .noneMatch(item -> item.source().equals("ai"));
        }
    }

    @Test
    void deferredDiscardCannotMissAnOffloadWhileWaitingForTheLossLock() throws Exception {
        var journal = journal(100, 1_000_000);
        var deferred = journal.beginCapture(JournalSource.SQL);
        assertThat(deferred).isNotNull();
        var ring = RuntimeJournal.class.getDeclaredField("ring");
        ring.setAccessible(true);
        var completion = new java.util.concurrent.FutureTask<Void>(() -> {
            deferred.discard(requestSql("request", 2_010));
            return null;
        });
        Thread recorder = new Thread(completion, "deferred-discard-probe");
        synchronized (ring.get(journal)) {
            recorder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (recorder.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(1);
            }
            assertThat(recorder.getState()).isEqualTo(Thread.State.BLOCKED);
            assertThat(journal.offloadRetainedData()).isZero();
        }
        completion.get(5, TimeUnit.SECONDS);
        assertThat(journal.lossHorizonMillis()).isEqualTo(2_010L);
        assertThat(journal.hasUnsequencedLoss())
                .as("a genuine deferred loss belongs to the recording regardless of admission swap timing")
                .isTrue();
        assertThat(journal.status().accepted()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void deferredSqlRejectedAfterOffloadOnlyQualifiesItsOwnRecording(boolean clear, boolean discardedByRecorder)
            throws Exception {
        RuntimeJournal journal = journal(100, 1_000_000);
        var deferred = journal.beginCapture(JournalSource.SQL);
        assertThat(deferred).isNotNull();
        if (clear) {
            journal.clear();
        }
        assertThat(journal.offer(requestHttp("request", clear ? 3_000 : 2_000))).isTrue();
        journal.dispatchPending();
        var store = store();
        var scope = scope(journal);
        var identity = durableIdentity(journal, scope);
        store.upsertRun(new DurableJournalRun(
                scope,
                1,
                1,
                "RUNNING",
                null,
                1,
                identity.instanceId(),
                runMetadata(journal, identity, DurableJournalRunMetadata.Phase.CHECKPOINT)));
        persist(store, scope, journal.entries());
        assertThat(journal.offloadRetainedData()).isOne();
        var accepted = journal.status().accepted();
        var completion = requestSql("request", clear ? 3_010 : 2_010);
        if (discardedByRecorder) {
            deferred.discard(completion);
        } else {
            assertThat(deferred.offer(completion)).isFalse();
        }
        assertThat(journal.status().accepted()).isEqualTo(accepted);
        assertThat(journal.status().dropped()).isEmpty();
        assertThat(journal.awaitDrained(Duration.ZERO)).isTrue();

        var snapshot = new DurableJournalEvidence(store).read(scope, journal, new DurableJournalCapture.ReadWindow(0));
        assertThat(snapshot.omittedEvents()).isZero();
        assertThat(snapshot.lossHorizonMillis()).isEqualTo(clear ? null : 2_010L);
        assertThat(new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                        .reportFrom(snapshot)
                        .window()
                        .requests())
                .isEqualTo(clear ? 1 : 0);

        assertThat(journal.offer(requestHttp("unrelated", 4_000))).isTrue();
        journal.dispatchPending();
        persist(store, scope, journal.entries());
        assertThat(journal.offloadRetainedData()).isOne();
        for (long sequence = 1; sequence <= journal.lastSequence(); sequence++) {
            store.upsertDetail(DurableJournalDetail.request(scope, sequence, "GET", "/orders/{id}", "get", 200));
        }
        var metadata = DurableJournalRunMetadata.snapshot(
                DurableJournalRunMetadata.Phase.END,
                journal,
                identity,
                InsightsStack.SPRING_MVC,
                Map.of(),
                null,
                RunSummary.of(
                        journal.run(),
                        new JournalAggregates().snapshot(),
                        journal.run().startedAtEpochMillis()));
        store.upsertRun(new DurableJournalRun(
                scope, 1, 2, "STOPPED", null, journal.lastSequence(), identity.instanceId(), metadata));
        assertThat(store.findRunMetadata(scope).phase()).isEqualTo(DurableJournalRunMetadata.Phase.END);

        var archive = DurableJournalArchiveService.forNamespace(store, null, scope.namespace());
        var archived = archive.multiRunInsights(List.of(scope)).runs().get(0);
        assertThat(archived.completeness().omittedEvents()).isZero();
        assertThat(archived.insights().window().requests())
                .as("archived Insights must exclude the request whose deferred SQL was lost, not the later request")
                .isEqualTo(clear ? 2 : 1);
        assertThat(archived.completeness().complete()).isEqualTo(clear);
        var evidence = new DurableJournalEvidence(store).read(scope);
        assertThat(evidence.lostMillis(JournalSource.SQL)).isEqualTo(clear ? null : 2_010L);
        assertThat(evidence.evictedARequestOf("unrelated-trace")).isFalse();
        assertThat(archive.profile(scope, "request").notes().stream()
                        .anyMatch(note -> note.startsWith("Deferred SQL was lost before it could be recorded")))
                .isEqualTo(!clear);
    }

    @Test
    void aHotEvictionDoesNotCreateAPersistedTraceVeto() {
        RuntimeJournal journal = journal(1, 1_000_000);
        assertThat(journal.offer(tracedHttp("hot-eviction", 1_000, "hot-only"))).isTrue();
        assertThat(journal.offer(requestHttp("retained", 2_000))).isTrue();
        journal.dispatchPending();
        assertThat(journal.evictedARequestOf("hot-only")).isTrue();
        assertThat(journal.offloadRetainedData()).isOne();

        var scope = scope(journal);
        var metadata = runMetadata(journal, durableIdentity(journal, scope), DurableJournalRunMetadata.Phase.END);
        assertThat(metadata.offloadTraceLoss().isEmpty()).isTrue();
    }

    @Test
    void aWriterQueueDropVetoesOnlyItsOwnTraceInHistoricalProfiles() {
        RuntimeJournal journal = journal(100, 1_000_000);
        JdbcDurableJournalStore store = store();
        var scope = scope(journal);
        var identity = durableIdentity(journal, scope);
        var settings = new DurableJournalCaptureSettings(
                4, 16_000, 4, 16_000, 4, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(2));
        try (var capture = captureWithMetadata(journal, identity, store, settings)) {
            assertThat(journal.offer(tracedHttp("writer-drop", 1_000, "shared")))
                    .isTrue();
            assertThat(journal.offer(tracedHttp("retained", 2_000, "shared"))).isTrue();
            assertThat(journal.offer(tracedAi(2_000, "shared"))).isTrue();
            assertThat(journal.offer(tracedHttp("unrelated", 3_000, "different")))
                    .isTrue();
            assertThat(journal.offer(tracedAi(3_000, "different"))).isTrue();
            journal.dispatchPending();
            assertThat(capture.status().dropped()).isOne();
            assertThat(journal.offloadRetainedData()).isEqualTo(5);
        }

        var metadata = store.findRunMetadata(scope);
        assertThat(metadata.durableDroppedEvents()).isOne();
        assertThat(metadata.lostRequestTraces()).isEmpty();
        var snapshot = new DurableJournalEvidence(store).read(scope);
        assertThat(snapshot.omittedEvents()).isOne();
        var profiles = new RequestJournalProfiles(journal, null, 0, 2, null);
        assertThat(profiles.profileFrom(snapshot, "retained").timeline())
                .noneMatch(item -> item.source().equals("ai"));
        assertThat(profiles.profileFrom(snapshot, "unrelated").timeline())
                .anyMatch(item -> item.source().equals("ai"));
        assertThat(metadata.offloadTraceLoss().matches(scope, "shared")).isTrue();
    }

    @Test
    void aWriterConflictReturningAfterClearCannotVetoTheNewRecording() throws Exception {
        RuntimeJournal journal = journal(100, 1_000_000);
        JdbcDurableJournalStore store = spy(store());
        var scope = scope(journal);
        var identity = durableIdentity(journal, scope);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    List<DurableJournalEvent> events = invocation.getArgument(1);
                    if (events.get(0).entry().sequence() == 1) {
                        writing.countDown();
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        throw new DurableJournalConflictException(scope, 1);
                    }
                    return invocation.callRealMethod();
                })
                .when(store)
                .appendEvents(any(), any());
        var settings = new DurableJournalCaptureSettings(
                64, 16_000, 64, 16_000, 8, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(2));
        try (var capture = captureWithMetadata(journal, identity, store, settings)) {
            try {
                assertThat(journal.offer(tracedHttp("old", 1_000, "shared"))).isTrue();
                journal.dispatchPending();
                capture.flushNow();
                assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
                journal.clear();
                assertThat(journal.offer(tracedHttp("new", 2_000, "shared"))).isTrue();
                assertThat(journal.offer(tracedAi(2_000, "shared"))).isTrue();
                journal.dispatchPending();
            } finally {
                release.countDown();
            }
        }
        assertThat(journal.evictedARequestOf("shared")).isFalse();
        assertThat(journal.lossHorizonMillis()).isEqualTo(1_000L);
        var metadata = store.findRunMetadata(scope);
        assertThat(metadata.durableQuarantinedEvents()).isZero();
        assertThat(metadata.offloadTraceLoss().isEmpty()).isTrue();
        var snapshot = new DurableJournalEvidence(store).read(scope, journal, new DurableJournalCapture.ReadWindow(1));
        assertThat(new RequestJournalProfiles(journal, null, 0, 2, null)
                        .profileFrom(snapshot, "new")
                        .timeline())
                .anyMatch(item -> item.source().equals("ai"));
    }

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
    void anEarlierDiscardDoesNotHideALaterOffloadedRequestUnlessItsEvidenceIsMissing(boolean missingChild) {
        RuntimeJournal journal = journal(100, 1_000_000);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        assertThat(journal.offer(requestHttp("earlier", 1_000))).isTrue();
        journal.dispatchPending();
        List<JournalEntry> earlier = journal.entries();
        assertThat(journal.offer(requestSql("earlier", 1_010))).isTrue();
        assertThat(journal.offloadRetainedData()).isEqualTo(2);

        assertThat(journal.offer(requestSql("later", 2_010))).isTrue();
        assertThat(journal.offer(requestHttp("later", 2_000))).isTrue();
        journal.dispatchPending();
        List<JournalEntry> later = journal.entries();
        assertThat(journal.offloadRetainedData()).isEqualTo(2);

        DurableJournalIdentity identity = new DurableJournalIdentity(
                scope.namespace(),
                scope.application(),
                scope.run(),
                "process",
                journal.status().instanceId());
        var metadata = DurableJournalRunMetadata.snapshot(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                journal,
                identity,
                InsightsStack.SPRING_MVC,
                Map.of(),
                null,
                null);
        store.upsertRun(new DurableJournalRun(
                scope, 1, 1, "RUNNING", null, journal.lastSequence(), identity.instanceId(), metadata));
        persist(store, scope, earlier);
        persist(
                store,
                scope,
                later.stream()
                        .filter(entry -> !missingChild || entry.event().source() != JournalSource.SQL)
                        .toList());

        DurableJournalEvidence evidence = new DurableJournalEvidence(store);
        assertThat(journal.lossHorizonMillis()).isEqualTo(2_010L);
        assertThat(evidence.read(scope).lossHorizonMillis()).isEqualTo(missingChild ? 2_010L : null);
        JournalEvidenceSnapshot snapshot = evidence.read(scope, journal, new DurableJournalCapture.ReadWindow(0));
        assertThat(snapshot.omittedEvents()).isEqualTo(missingChild ? 1 : 0);
        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).reportFrom(snapshot);
        assertThat(report.window().requests()).isEqualTo(missingChild ? 0 : 1);
        assertThat(snapshot.lossHorizonMillis()).isEqualTo(missingChild ? 2_010L : 1_010L);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void offloadPreservesAnOrdinaryHotEvictionVetoUnlessTheArchiveRecoversTheRequest(boolean requestMissing) {
        String traceId = "shared-trace";
        RuntimeEvent first = tracedHttp("first", 1_000, traceId);
        RuntimeEvent next = requestHttp("next", 2_000);
        long maxBytes = first.estimatedBytes() + next.estimatedBytes();
        RuntimeJournal journal = journal(100, maxBytes);
        String dictionaryOnly = "x"
                .repeat(Math.toIntExact(
                        maxBytes * RuntimeJournal.DICTIONARY_BYTES_PERCENT / 100 - RuntimeEvent.stringBytes("") - 48));
        // Fill the dictionary with unrelated text so the hot ring cannot keep both rows, unlike the archive.
        assertThat(journal.dictionary().intern(dictionaryOnly)).isNotEqualTo(JournalDictionary.NOT_INTERNED);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        DurableJournalIdentity identity = new DurableJournalIdentity(
                scope.namespace(),
                scope.application(),
                scope.run(),
                "process",
                journal.status().instanceId());
        assertThat(journal.offer(first)).isTrue();
        journal.dispatchPending();
        JournalEntry evicted = journal.entries().get(0);
        assertThat(journal.offer(next)).isTrue();
        journal.dispatchPending();
        List<JournalEntry> entries = journal.entries();
        assertThat(entries).extracting(JournalEntry::sequence).containsExactly(2L);
        assertThat(journal.evictedARequestOf(traceId)).isTrue();
        assertThat(journal.lostArchiveTrace(traceId)).isFalse();
        assertThat(journal.offloadRetainedData()).isOne();
        var metadata = DurableJournalRunMetadata.snapshot(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                journal,
                identity,
                InsightsStack.SPRING_MVC,
                Map.of(),
                null,
                null);
        store.upsertRun(new DurableJournalRun(scope, 1, 1, "RUNNING", null, 2, identity.instanceId(), metadata));
        persist(store, scope, requestMissing ? entries : List.of(evicted, entries.get(0)));
        assertThat(store.findRunMetadata(scope).lostRequestTraces()).isEmpty();
        assertThat(journal.evictedARequestOf(traceId)).isFalse();

        DurableJournalEvidence reader = new DurableJournalEvidence(store);
        JournalEvidenceSnapshot snapshot = reader.read(scope, journal, new DurableJournalCapture.ReadWindow(0));
        assertThat(snapshot.omittedEvents()).isEqualTo(requestMissing ? 1 : 0);
        assertThat(snapshot.evictedARequestOf(traceId)).isEqualTo(requestMissing);

        journal.clear();
        assertThat(journal.offer(requestHttp("not-persisted", 3_000))).isTrue();
        journal.dispatchPending();
        assertThat(journal.offloadRetainedData()).isEqualTo(1);
        JournalEvidenceSnapshot afterClear = reader.read(scope, journal, new DurableJournalCapture.ReadWindow(2));
        assertThat(afterClear.omittedEvents()).isOne();
        assertThat(afterClear.evictedARequestOf(traceId)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyMemoryOffloadVetoesAttributionAfterDiscardingAnUndispatchedRequest(boolean memoryOffload) {
        RuntimeJournal journal = journal(100, 1_000_000);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        String traceId = "shared-trace";
        assertThat(journal.offer(tracedHttp("never-dispatched", 1_000, traceId)))
                .isTrue();
        if (memoryOffload) {
            assertThat(journal.offloadRetainedData()).isOne();
        } else {
            journal.clear();
        }
        assertThat(journal.lastSequence()).isZero();

        assertThat(journal.offer(tracedHttp("later", 2_000, traceId))).isTrue();
        assertThat(journal.offer(tracedAi(2_000, traceId))).isTrue();
        assertThat(journal.offer(tracedHttp("unrelated", 3_000, "other-trace"))).isTrue();
        assertThat(journal.offer(tracedAi(3_000, "other-trace"))).isTrue();
        journal.dispatchPending();
        DurableJournalIdentity identity = new DurableJournalIdentity(
                scope.namespace(),
                scope.application(),
                scope.run(),
                "process",
                journal.status().instanceId());
        var metadata = DurableJournalRunMetadata.snapshot(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                journal,
                identity,
                InsightsStack.SPRING_MVC,
                Map.of(),
                null,
                null);
        assertThat(metadata.lostRequestTraces()).isEmpty();
        store.upsertRun(new DurableJournalRun(scope, 1, 1, "RUNNING", null, 4, identity.instanceId(), metadata));
        assertThat(new String(store.findRun(scope).metadataPayload(), StandardCharsets.ISO_8859_1))
                .doesNotContain(traceId);
        assertThat(store.findRunMetadata(scope).offloadTraceLoss().matches(scope, traceId))
                .isEqualTo(memoryOffload);
        persist(store, scope, journal.entries());
        assertThat(journal.offloadRetainedData()).isEqualTo(4);

        DurableJournalEvidence reader = new DurableJournalEvidence(store);
        JournalEvidenceSnapshot current = reader.read(scope, journal, new DurableJournalCapture.ReadWindow(0));
        assertThat(current.omittedEvents()).isZero();
        assertThat(current.lossHorizonMillis()).isEqualTo(memoryOffload ? 1_000L : null);
        var profiles = new RequestJournalProfiles(journal, null, 0, 2, null);
        var profile = profiles.profileFrom(current, "later");
        assertThat(profile.available()).isTrue();
        assertThat(profile.timeline().stream().anyMatch(item -> item.source().equals("ai")))
                .as("the trace-only call must not be attached if another request with that trace was lost in offload")
                .isEqualTo(!memoryOffload);
        JournalEvidenceSnapshot archived = reader.read(scope);
        assertThat(archived.omittedEvents()).isZero();
        assertThat(profiles.profileFrom(archived, "later").timeline().stream()
                        .anyMatch(item -> item.source().equals("ai")))
                .as("archiving the same evidence must not revive attribution lost by offload")
                .isEqualTo(!memoryOffload);
        assertThat(profiles.profileFrom(archived, "unrelated").timeline())
                .anyMatch(item -> item.source().equals("ai"));
        assertThat(journal.evictedARequestOf(traceId)).isFalse();

        journal.clear();
        store.clearRunThrough(scope, 4);
        assertThat(journal.offer(tracedHttp("new-recording", 4_000, traceId))).isTrue();
        assertThat(journal.offer(tracedAi(4_000, traceId))).isTrue();
        journal.dispatchPending();
        persist(store, scope, journal.entries());
        assertThat(journal.offloadRetainedData()).isEqualTo(2);
        assertThat(store.findRunMetadata(scope).offloadTraceLoss().matches(scope, traceId))
                .isEqualTo(memoryOffload);
        JournalEvidenceSnapshot beforeHeartbeat = reader.read(scope, journal, new DurableJournalCapture.ReadWindow(4));
        assertThat(beforeHeartbeat.entries()).extracting(JournalEntry::sequence).containsExactly(6L, 5L);
        assertThat(beforeHeartbeat.lossHorizonMillis())
                .as("a complete new recording does not inherit unsequenced loss from before Clear")
                .isNull();
        assertThat(profiles.profileFrom(beforeHeartbeat, "new-recording").timeline())
                .as("the previous recording's checkpoint must not veto attribution in the current recording")
                .anyMatch(item -> item.source().equals("ai"));

        var afterClear = DurableJournalRunMetadata.snapshot(
                        DurableJournalRunMetadata.Phase.CHECKPOINT,
                        journal,
                        identity,
                        InsightsStack.SPRING_MVC,
                        Map.of(),
                        null,
                        null)
                .withCaptureLoss(0, 0, 4);
        store.upsertRun(new DurableJournalRun(scope, 1, 2, "RUNNING", null, 6, identity.instanceId(), afterClear));
        assertThat(store.findRunMetadata(scope).offloadTraceLoss().isEmpty()).isTrue();
        assertThat(profiles.profileFrom(reader.read(scope), "new-recording").timeline())
                .anyMatch(item -> item.source().equals("ai"));
    }

    @Test
    void offloadTraceOverflowConservativelyVetoesUntrackedTracesUntilClear() {
        RuntimeJournal journal = journal(100, 1_000_000);
        DurableJournalScope scope = scope(journal);
        for (int i = 0; i <= DurableJournalRunMetadata.OffloadTraceLoss.MAX_FINGERPRINTS; i++) {
            assertThat(journal.offer(tracedHttp("never-dispatched", 1_000, "trace-" + i)))
                    .isTrue();
            assertThat(journal.offloadRetainedData()).isOne();
        }

        var traceLoss = journal.durableTraceSnapshot(scope).offloadTraceLoss();
        assertThat(traceLoss.fingerprints()).isEmpty();
        assertThat(traceLoss.matches(scope, "untracked")).isTrue();
        assertThat(journal.evictedARequestOf("untracked", true)).isTrue();
        assertThat(journal.evictedARequestOf("untracked")).isFalse();
        journal.clear();
        assertThat(journal.durableTraceSnapshot(scope).offloadTraceLoss().isEmpty())
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDispatcherHeldRequestBelongsToItsOriginalOffloadGeneration(boolean followedByClear) throws Exception {
        RuntimeJournal journal = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start());
        journals.add(journal);
        var processing = RuntimeJournal.class.getDeclaredField("processing");
        processing.setAccessible(true);
        DurableJournalScope scope = scope(journal);
        String trace = "dispatcher-held-trace";
        synchronized (processing.get(journal)) {
            assertThat(journal.offer(tracedHttp("held", 1_000, trace))).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (journal.status().queueDepth() != 0 && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(1);
            }
            assertThat(journal.status().queueDepth()).isZero();
            assertThat(journal.lastSequence()).isZero();
            assertThat(journal.offloadRetainedData()).isZero();
            assertThat(journal.durableTraceSnapshot(scope).offloadTraceLoss().matches(scope, trace))
                    .as("detachment records trace loss before the dispatcher can resume")
                    .isTrue();
            assertThat(journal.hasUnsequencedLoss()).isTrue();
            if (followedByClear) {
                journal.clear();
                assertThat(journal.durableTraceSnapshot(scope)
                                .offloadTraceLoss()
                                .isEmpty())
                        .isTrue();
            }
        }
        assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        assertThat(journal.durableTraceSnapshot(scope).offloadTraceLoss().matches(scope, trace))
                .isEqualTo(!followedByClear);
        assertThat(journal.evictedARequestOf(trace)).isFalse();
    }

    @Test
    void recoveringOffloadedWorkDoesNotHideItsLaterUnlinkedLog() {
        RuntimeJournal journal = journal(100, 1_000_000);
        JdbcDurableJournalStore store = store();
        DurableJournalScope scope = scope(journal);
        assertThat(journal.offer(requestHttp("lost", 1_000, "earlier-thread"))).isTrue();
        assertThat(journal.offloadRetainedData()).isEqualTo(1);

        assertThat(journal.offer(unlinkedError(1_010, "earlier-thread"))).isTrue();
        assertThat(journal.offer(requestHttp("recovered", 3_000))).isTrue();
        assertThat(journal.offer(unlinkedError(3_010, "http-1"))).isTrue();
        journal.dispatchPending();
        List<JournalEntry> retained = journal.entries();
        assertThat(journal.offloadRetainedData()).isEqualTo(3);

        DurableJournalIdentity identity = new DurableJournalIdentity(
                scope.namespace(),
                scope.application(),
                scope.run(),
                "process",
                journal.status().instanceId());
        var metadata = DurableJournalRunMetadata.snapshot(
                DurableJournalRunMetadata.Phase.CHECKPOINT,
                journal,
                identity,
                InsightsStack.SPRING_MVC,
                Map.of(),
                null,
                null);
        store.upsertRun(new DurableJournalRun(
                scope, 1, 1, "RUNNING", null, journal.lastSequence(), identity.instanceId(), metadata));
        persist(store, scope, retained);

        JournalEvidenceSnapshot snapshot =
                new DurableJournalEvidence(store).read(scope, journal, new DurableJournalCapture.ReadWindow(0));
        assertThat(snapshot.omittedEvents()).isZero();
        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).reportFrom(snapshot);
        assertThat(report.coverage())
                .filteredOn(coverage -> coverage.source().equals("log"))
                .singleElement()
                .satisfies(logs -> assertThat(logs.unlinked()).isEqualTo(1));
        assertThat(snapshot.lossHorizonMillis()).isEqualTo(1_000L);
        assertThat(snapshot.lostRequestEndMillis()).isEqualTo(1_001L);
        assertThat(snapshot.lostARequestOn("earlier-thread")).isTrue();
        assertThat(snapshot.lostARequestOn("http-1")).isFalse();
        assertThat(snapshot.lostMillis(JournalSource.HTTP)).isEqualTo(1_000L);
        assertThat(snapshot.lostMillis(JournalSource.LOG)).isNull();
        assertThat(journal.lostRequestEndMillis()).isEqualTo(3_001L);
        assertThat(journal.lostARequestOn("http-1")).isTrue();
        assertThat(journal.lostMillis(JournalSource.LOG)).isEqualTo(3_010L);
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

    private static DurableJournalIdentity durableIdentity(RuntimeJournal journal, DurableJournalScope scope) {
        return new DurableJournalIdentity(
                scope.namespace(),
                scope.application(),
                scope.run(),
                "process",
                journal.status().instanceId());
    }

    private static DurableJournalRunMetadata runMetadata(
            RuntimeJournal journal, DurableJournalIdentity identity, DurableJournalRunMetadata.Phase phase) {
        return DurableJournalRunMetadata.snapshot(
                phase, journal, identity, InsightsStack.SPRING_MVC, Map.of(), null, null);
    }

    private static DurableJournalCapture captureWithMetadata(
            RuntimeJournal journal,
            DurableJournalIdentity identity,
            DurableJournalStore store,
            DurableJournalCaptureSettings settings) {
        return DurableJournalCapture.start(
                journal,
                new DurableJournalSettings(
                        identity, true, Duration.ofMinutes(1), 64, 16_000, 100, "journal", Duration.ofDays(7), false),
                store,
                new JournalActivityFeed(0, 2, null),
                settings,
                List.of(),
                phase -> runMetadata(journal, identity, phase));
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

    private static RuntimeEvent tracedHttp(String requestId, long start, String traceId) {
        return RuntimeEvent.of(
                JournalSource.HTTP,
                start,
                10_000_000,
                CorrelationContext.forRequest(requestId).withTrace(traceId, null),
                "http-1",
                ThreadKind.WORKER,
                false,
                new HttpPayload("GET", "/orders/42", "/orders/{id}", null, 200));
    }

    private static RuntimeEvent tracedAi(long start, String traceId) {
        return RuntimeEvent.of(
                JournalSource.AI,
                start,
                1_000_000,
                CorrelationContext.NONE.withTrace(traceId, null),
                "ai-worker",
                ThreadKind.WORKER,
                false,
                new AiPayload("chat", "openai", "gpt-4o", 1L, 1L, "stop", false));
    }

    private static RuntimeEvent requestHttp(String requestId, long start) {
        return requestHttp(requestId, start, "http-1");
    }

    private static RuntimeEvent requestHttp(String requestId, long start, String thread) {
        return RuntimeEvent.of(
                JournalSource.HTTP,
                start,
                1_000_000,
                CorrelationContext.forRequest(requestId),
                thread,
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

    private static RuntimeEvent unlinkedError(long start, String thread) {
        return RuntimeEvent.of(
                JournalSource.LOG,
                start,
                0,
                null,
                thread,
                ThreadKind.WORKER,
                false,
                new LogPayload("org.springframework.web.DispatcherServlet", "ERROR", "Synthetic dispatch error", null));
    }
}
