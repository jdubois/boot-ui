package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DurableJournalClearEvidenceTests {

    @Test
    void aCommittedClearIsNotAnOmissionButALaterGapAndReadBoundsStillAre() throws Exception {
        try (Recording recording = new Recording()) {
            recording.recordAndClear(false);
            assertEvidence(recording.read(100), 0, 4L, 3L);
            assertEvidence(recording.read(2), 0, 4L, 3L);
            assertEvidence(recording.read(1), 1, 4L);
            assertEvidence(recording.read(100, recording.eventBytes()), 1, 4L);

            recording.deleteRow(3);

            assertEvidence(recording.read(100), 1, 4L);
            assertEvidence(recording.read(2), 1, 4L);
        }
    }

    @Test
    void aFailedDurableClearDoesNotHideRetainedRowsOrCertifyABoundedRead() throws Exception {
        try (Recording recording = new Recording()) {
            recording.recordAndClear(true);
            recording.assertUnframed();
            assertEvidence(recording.read(100), 0, 4L, 3L, 2L, 1L);
            assertEvidence(recording.read(2), 2, 4L, 3L);
        }
    }

    @Test
    void aLaterFailedClearDoesNotAdvanceOrEraseAnEarlierConfirmedBoundary() throws Exception {
        try (Recording recording = new Recording()) {
            recording.recordPair();
            recording.awaitDurableThrough(2);
            recording.journal.clear();
            recording.recordPair();
            recording.awaitDurableThrough(4);
            recording.preventDeletionOf(3);
            recording.journal.clear();
            recording.capture.close();

            assertThat(recording.capture.status().lastError())
                    .contains("Durable journal clear through sequence 4 failed");
            var archived = recording.store.findArchivedMetadata(recording.scope);
            assertThat(archived.metadata().durableClearedThrough()).isEqualTo(4);
            assertThat(archived.confirmedClearThrough()).isEqualTo(2);
            assertThat(recording.read(2).omittedEvents()).isZero();
            assertThat(recording.read(1).omittedEvents()).isEqualTo(1);
        }
    }

    @Test
    void captureWithoutClearKeepsTheExistingMetadataEncoding() {
        try (Recording recording = new Recording()) {
            recording.recordPair();
            recording.capture.close();

            recording.assertUnframed();
            assertThat(recording.read(2).partial()).isFalse();
        }
    }

    @Test
    void queueDropsAfterACommittedClearStillQualifyTheArchiveWithoutAddingOmissions() throws Exception {
        try (Recording recording = new Recording()) {
            recording.recordPair();
            recording.awaitDurableThrough(2);
            recording.journal.clear();
            for (int i = 0; i <= recording.journal.settings().queueCapacity(); i++) {
                recording.journal.offer(Recording.event());
            }
            recording.journal.dispatchPending();
            recording.capture.close();

            assertThat(recording.read(100).status().droppedTotal()).isPositive();
            var report = DurableJournalArchiveService.forNamespace(recording.store, null, recording.scope.namespace())
                    .multiRunInsights(List.of(recording.scope));
            assertThat(report.runs()).singleElement().satisfies(run -> {
                assertThat(run.completeness().omittedEvents()).isZero();
                assertThat(run.completeness().complete()).isFalse();
                assertThat(run.completeness().limitations()).anyMatch(reason -> reason.contains("admission queue"));
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aCompletePostClearRangeQualifiesOnlyWorkThatMayHaveSpannedClear(boolean crossesClear) throws Exception {
        try (Recording recording = new Recording()) {
            long start = System.currentTimeMillis();
            recording.journal.offer(new RuntimeEvent(
                    JournalSource.SQL,
                    start + 1,
                    1,
                    "request",
                    null,
                    null,
                    "fixture",
                    ThreadKind.WORKER,
                    false,
                    new SqlPayload("select 1", "Repo.find", "test", false)));
            recording.journal.dispatchPending();
            recording.awaitDurableThrough(1);
            recording.journal.clear();
            recording.journal.offer(request("request", crossesClear ? start : start + 50, 20_000_000));
            recording.journal.offer(request("fresh", start + 100, 2_000_000));
            recording.journal.dispatchPending();
            recording.capture.close();

            var evidence = recording.read(2);
            assertThat(evidence.omittedEvents()).isZero();
            assertThat(evidence.partial()).isFalse();
            assertThat(evidence.lossHorizonMillis()).isGreaterThanOrEqualTo(start);
            assertThat(evidence.lostMillis(JournalSource.SQL)).isEqualTo(start + 1);

            var profiles = new RequestJournalProfiles(null, null, 0, 2, null);
            var profile = profiles.profileFrom(evidence, "request", "fresh");
            assertThat(profile.available()).isTrue();
            if (crossesClear) {
                assertThat(profile.baselineComparison()).isNull();
                assertThat(profile.notes()).anyMatch(note -> note.contains("journal's loss horizon"));
            }

            assertThat(recording.store.listDetails(recording.scope)).hasSize(2);
            var report = DurableJournalArchiveService.forNamespace(recording.store, null, recording.scope.namespace())
                    .multiRunInsights(List.of(recording.scope));
            assertThat(report.runs()).singleElement().satisfies(run -> {
                assertThat(run.completeness().omittedEvents()).isZero();
                assertThat(run.completeness().complete()).isEqualTo(!crossesClear);
                assertThat(run.insights().window().requests()).isEqualTo(crossesClear ? 1 : 2);
                if (crossesClear) {
                    assertThat(run.completeness().limitations())
                            .anyMatch(note -> note.contains("started before events the journal evicted or cleared"));
                }
            });
        }
    }

    private static RuntimeEvent request(String requestId, long start, long durationNanos) {
        return new RuntimeEvent(
                JournalSource.HTTP,
                start,
                durationNanos,
                requestId,
                null,
                null,
                "fixture",
                ThreadKind.WORKER,
                false,
                new HttpPayload("GET", "/orders", "/orders", "Orders.get", 200));
    }

    @Test
    void aLegacyClearAttemptDoesNotExplainAnAbsentPrefixOrCertifyABoundedRead() throws Exception {
        try (Recording recording = new Recording()) {
            recording.recordAndClear(false);
            recording.store.upsertRun(recording
                    .store
                    .findRun(recording.scope)
                    .withMetadataPayload(
                            DurableJournalRunMetadataCodec.encode(recording.store.findRunMetadata(recording.scope))));

            // The old snapshot is indistinguishable from an unconfirmed delete or an unrelated missing prefix.
            assertEvidence(recording.read(100), 2, 4L, 3L);
            assertEvidence(recording.read(2), 2, 4L, 3L);
        }
    }

    private static void assertEvidence(JournalEvidenceSnapshot evidence, long omitted, Long... sequences) {
        assertThat(evidence.entries()).extracting(JournalEntry::sequence).containsExactly(sequences);
        assertThat(evidence.omittedEvents()).isEqualTo(omitted);
        assertThat(evidence.partial()).isEqualTo(omitted != 0);
        assertThat(evidence.durableCaptureLoss()).isZero();
        assertThat(evidence.status().accepted()).containsEntry(JournalSource.SQL, 4L);
        assertThat(evidence.status().clears()).isEqualTo(1);
    }

    private static final class Recording implements AutoCloseable {

        private final RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 10_000_000, 100, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false);
        private final JdbcDataSource dataSource = new JdbcDataSource();
        private final JdbcDurableJournalStore store;
        private final DurableJournalScope scope;
        private final DurableJournalCapture capture;

        private Recording() {
            dataSource.setURL("jdbc:h2:mem:clear-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            store = new JdbcDurableJournalStore(dataSource, "journal");
            DurableJournalIdentity identity = new DurableJournalIdentity(
                    "local",
                    "orders",
                    journal.run().id(),
                    "123",
                    journal.status().instanceId());
            Duration interval = Duration.ofMinutes(1);
            DurableJournalSettings settings = new DurableJournalSettings(
                    identity, true, interval, 100, 1_000_000, 100, "journal", Duration.ofDays(1), false);
            scope = settings.scope();
            capture = DurableJournalCapture.start(
                    journal,
                    settings,
                    store,
                    new JournalActivityFeed(0, 2, null),
                    DurableJournalCaptureSettings.of(settings),
                    List.of(new HttpPayloadDurableJournalDetailProvider()),
                    phase -> DurableJournalRunMetadata.snapshot(
                            phase,
                            journal,
                            identity,
                            InsightsStack.SPRING_MVC,
                            Map.of(),
                            null,
                            phase == DurableJournalRunMetadata.Phase.END
                                    ? RunSummary.of(
                                            journal.run(),
                                            new JournalAggregates().snapshot(),
                                            System.currentTimeMillis())
                                    : null));
        }

        private void recordAndClear(boolean failClear) throws Exception {
            recordPair();
            awaitDurableThrough(2);
            assertThat(store.query(scope).entries())
                    .extracting(JournalEntry::sequence)
                    .containsExactly(1L, 2L);
            if (failClear) {
                preventDeletionOf(1);
            }

            journal.clear();
            recordPair();
            capture.close();

            if (failClear) {
                assertThat(capture.status().lastError()).contains("Durable journal clear through sequence 2 failed");
            }
            assertThat(store.query(scope).entries())
                    .extracting(JournalEntry::sequence)
                    .containsExactlyElementsOf(failClear ? List.of(1L, 2L, 3L, 4L) : List.of(3L, 4L));
            DurableJournalRunMetadata end = store.findRunMetadata(scope);
            assertThat(end.phase()).isEqualTo(DurableJournalRunMetadata.Phase.END);
            assertThat(end.durableClearedThrough()).isEqualTo(2);
        }

        private void assertUnframed() {
            assertThat(store.findRun(scope).metadataPayload())
                    .containsExactly(DurableJournalRunMetadataCodec.encode(store.findRunMetadata(scope)));
        }

        private void awaitDurableThrough(long sequence) throws InterruptedException {
            capture.flushNow();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (capture.status().durableWatermark() < sequence && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(capture.status().durableWatermark()).isEqualTo(sequence);
        }

        private void recordPair() {
            journal.offer(event());
            journal.offer(event());
            journal.dispatchPending();
        }

        private static RuntimeEvent event() {
            return new RuntimeEvent(
                    JournalSource.SQL,
                    System.currentTimeMillis(),
                    1,
                    null,
                    null,
                    null,
                    "fixture",
                    ThreadKind.WORKER,
                    false,
                    new SqlPayload("select 1", "Repo.find", "test", false));
        }

        private void preventDeletionOf(long sequence) throws SQLException {
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE retain_first (
                      global_sequence BIGINT NOT NULL REFERENCES journal_events (global_sequence)
                    )
                    """);
                try (var retain = connection.prepareStatement("""
                    INSERT INTO retain_first
                    SELECT global_sequence FROM journal_events WHERE journal_sequence = ?
                    """)) {
                    retain.setLong(1, sequence);
                    assertThat(retain.executeUpdate()).isEqualTo(1);
                }
            }
        }

        private void deleteRow(long sequence) throws SQLException {
            try (var connection = dataSource.getConnection();
                    var statement =
                            connection.prepareStatement("DELETE FROM journal_events WHERE journal_sequence = ?")) {
                statement.setLong(1, sequence);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        }

        private JournalEvidenceSnapshot read(int eventBound) {
            return read(eventBound, 10_000_000);
        }

        private JournalEvidenceSnapshot read(int eventBound, long byteBound) {
            return new DurableJournalEvidence(
                            new JdbcDurableJournalStore(dataSource, "journal", true), eventBound, byteBound)
                    .read(scope);
        }

        private long eventBytes() {
            return store.query(scope).entries().get(0).estimatedBytes();
        }

        @Override
        public void close() {
            capture.close();
            journal.close();
        }
    }
}
