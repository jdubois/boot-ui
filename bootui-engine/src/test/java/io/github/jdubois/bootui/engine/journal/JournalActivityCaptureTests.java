package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStore;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JournalActivityCaptureTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
            RunIdentity.start(),
            false);
    private final RecordingStore store = new RecordingStore();

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void eachRecordedBatchIsWrittenWithChildrenNamingTheirParentBeforeItIsRecorded() {
        JournalActivityCapture capture = start(null);

        for (int i = 0; i < 3; i++) {
            offer("r1", null, JournalSource.SQL, new SqlPayload("select * from lines where id = ?", null, "db", false));
        }
        offer(null, "e1", JournalSource.SQL, new SqlPayload("update stock set n = ?", null, "db", false));
        journal.dispatchPending();
        offer("r1", null, JournalSource.HTTP, new HttpPayload("GET", "/api/orders/1", "/api/orders/{id}", null, 200));
        offer(null, "e1", JournalSource.SCHEDULED, new ScheduledPayload("StockJob.run", null));
        journal.dispatchPending();

        Map<String, ActivityEntryDto> byType = store.entries().stream()
                .collect(Collectors.toMap(ActivityEntryDto::type, entry -> entry, (first, second) -> first));
        assertThat(store.entries()).hasSize(6);
        assertThat(store.entries())
                .filteredOn(entry -> entry.summary().startsWith("select"))
                .allSatisfy(entry -> assertThat(entry.parentId()).isEqualTo("r1"));
        assertThat(store.entries())
                .filteredOn(entry -> entry.summary().startsWith("update"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.parentId()).isEqualTo("e1"));
        assertThat(byType.get("REQUEST").id()).isEqualTo("r1");
        assertThat(byType.get("REQUEST").sqlNPlusOneSuspected())
                .as("SELECT counts carried over from the batch before the request completed")
                .isTrue();
        assertThat(byType.get("SCHEDULED").id()).isEqualTo("e1");
        assertThat(store.batches.get(0).get(0).seq())
                .as("the coordinator stamps each row in order")
                .isLessThan(store.batches.get(1).get(0).seq());

        capture.close();
        offer("r2", null, JournalSource.HTTP, new HttpPayload("GET", "/a", "/a", null, 200));
        journal.dispatchPending();
        assertThat(store.entries()).as("nothing after close").hasSize(6);
    }

    @Test
    void aDisabledPanelsRowsAreNotWrittenAndConnectionsAreNoRows() {
        start(panel -> !panel.equals(BootUiPanels.SQL_TRACE));

        offer("r1", null, JournalSource.SQL, new SqlPayload("select 1", null, "db", false));
        offer("r1", null, JournalSource.CONNECTION, new ConnectionPayload("db", 1, 1));
        offer("r1", null, JournalSource.HTTP, new HttpPayload("GET", "/a", "/a", null, 200));
        journal.dispatchPending();

        assertThat(store.entries()).extracting(ActivityEntryDto::type).containsExactly("REQUEST");
    }

    @Test
    void aNeverCompletingRequestKeepsOnlyBoundedLiteralFreeSelectFingerprints() {
        JournalActivityCapture capture = start(null);
        for (int i = 0; i < JournalActivityFeed.MAX_SELECTS_PER_REQUEST + 30; i++) {
            offer(
                    "r1",
                    null,
                    JournalSource.SQL,
                    new SqlPayload("select * from table_" + i + " where id = " + i, null, null, false));
            journal.dispatchPending();
        }
        assertThat(capture.pendingSelectCount()).isEqualTo(JournalActivityFeed.MAX_SELECTS_PER_REQUEST);
        assertThat(capture.overflowedSelects()).isEqualTo(30);
        offer(
                "r1",
                null,
                JournalSource.SQL,
                new SqlPayload("select * from table_45 where id = 999", null, null, false));
        journal.dispatchPending();
        assertThat(capture.pendingSelectCount()).isEqualTo(JournalActivityFeed.MAX_SELECTS_PER_REQUEST);
        assertThat(capture.overflowedSelects()).isEqualTo(30);
        capture.onClear();
        assertThat(capture.pendingSelectCount()).isZero();
    }

    @Test
    void aWideOrLateSelectStillRaisesThePersistedRequestNPlusOneFlag() {
        start(null);
        for (int i = 0; i < JournalActivityFeed.MAX_SELECTS_PER_REQUEST; i++) {
            offer("r1", null, JournalSource.SQL, new SqlPayload("select * from table_" + i, null, null, false));
        }
        String wide = "select " + "column_name_".repeat(60) + " from orders where id = ";
        for (int i = 0; i < 3; i++) {
            offer("r1", null, JournalSource.SQL, new SqlPayload(wide + i, null, null, false));
        }
        offer("r1", null, JournalSource.HTTP, http("/orders"));
        journal.dispatchPending();

        assertThat(store.entries())
                .filteredOn(row -> row.type().equals("REQUEST"))
                .singleElement()
                .satisfies(row -> assertThat(row.sqlNPlusOneSuspected()).isTrue());
    }

    @Test
    void literalVariantsHaveTheSameNPlusOneFlagInLiveAndPersistedRows() {
        start(null);
        for (int i = 0; i < 3; i++) {
            offer(
                    "r1",
                    null,
                    JournalSource.SQL,
                    new SqlPayload("select * from orders where id = " + i, null, null, false));
        }
        offer("r1", null, JournalSource.HTTP, http("/orders"));
        journal.dispatchPending();

        ActivityEntryDto persisted = store.entries().stream()
                .filter(row -> row.type().equals("REQUEST"))
                .findFirst()
                .orElseThrow();
        ActivityEntryDto live = new JournalActivityFeed(1_000, 3, null)
                        .render(journal.entries(), journal::eventId, "run", JournalActivityFeed.Filter.NONE, 0)
                        .entries()
                        .stream()
                        .filter(row -> row.type().equals("REQUEST"))
                        .findFirst()
                        .orElseThrow();
        assertThat(persisted.sqlNPlusOneSuspected()).isTrue().isEqualTo(live.sqlNPlusOneSuspected());
    }

    @Test
    void aHotSelectAfterSixteenRepeatedShapesCanStillBeTracked() {
        JournalActivityCapture capture = start(null);
        for (int i = 0; i < JournalActivityFeed.MAX_SELECTS_PER_REQUEST; i++) {
            for (int repeat = 0; repeat < 2; repeat++) {
                offer("r1", null, JournalSource.SQL, new SqlPayload("select * from table_" + i, null, null, false));
            }
        }
        for (int repeat = 0; repeat < 3; repeat++) {
            offer("r1", null, JournalSource.SQL, new SqlPayload("select * from late_table", null, null, false));
        }
        journal.dispatchPending();

        assertThat(capture.pendingSelectCount()).isEqualTo(JournalActivityFeed.MAX_SELECTS_PER_REQUEST);
        assertThat(capture.overflowedSelects()).isEqualTo(1);
        offer("r1", null, JournalSource.HTTP, http("/orders"));
        journal.dispatchPending();
        assertThat(store.entries())
                .filteredOn(row -> row.type().equals("REQUEST"))
                .singleElement()
                .satisfies(row -> assertThat(row.sqlNPlusOneSuspected()).isTrue());
    }

    @Test
    void anAiCallLinkedOnlyByItsTraceIsWrittenOnItsOwnAtOnce() {
        start(null);

        offerAt("r1", "trace-1", 1_000, 50_000_000, http("/api/chat"));
        offerAt(null, "trace-1", 1_010, 5_000_000, aiPayload());
        journal.dispatchPending();

        assertThat(store.entries())
                .extracting(ActivityEntryDto::type, ActivityEntryDto::parentId)
                .as("a request recorded later could change the inference, and a written row is never revised")
                .containsExactlyInAnyOrder(tuple("AI", null), tuple("REQUEST", null));
    }

    @Test
    void aBatchDeliveredAfterTheCaptureClosedIsNotWritten() {
        JournalActivityCapture capture = start(null);
        capture.close();

        capture.onEntries(List.of(new JournalEntry(
                1,
                RuntimeEvent.of(
                        JournalSource.AI,
                        1_000,
                        1,
                        CorrelationContext.NONE.withTrace("trace-1", null),
                        null,
                        null,
                        false,
                        aiPayload()),
                100)));

        assertThat(store.entries()).isEmpty();
    }

    @Test
    void anAiCallItsSpanTiedToARequestIsWrittenUnderItAtOnce() {
        start(null);

        journal.offer(RuntimeEvent.of(
                JournalSource.AI,
                1_010,
                5_000_000,
                CorrelationContext.forRequest("r1").withTrace("trace-1", null),
                null,
                null,
                false,
                aiPayload()));
        journal.dispatchPending();

        assertThat(store.entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.parentId()).isEqualTo("r1"));
    }

    /**
     * §5.3's loss with persistence on: below the queue bound nothing is lost, even at a rate whose events the journal's
     * retained rows have already evicted before anything reads them, because persistence is a listener of every
     * recorded batch rather than a poller of a window, as 1.x was above about 100 events per second.
     */
    @Test
    void everyRequestBelowTheQueueBoundIsWrittenEvenOnceTheRetainedRowsEvictedIt() {
        start(null);
        int requests = 2_500;
        for (int i = 0; i < requests; i++) {
            journal.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000 + i,
                    1_000_000,
                    CorrelationContext.forRequest("r" + i),
                    "t",
                    null,
                    false,
                    http("/api/orders")));
            if (i % 500 == 499) {
                journal.dispatchPending();
            }
        }
        journal.dispatchPending();

        assertThat(journal.entries()).as("the retained rows' bound").hasSize(1_000);
        assertThat(journal.status().droppedTotal()).isZero();
        assertThat(store.entries()).hasSize(requests).allSatisfy(entry -> assertThat(entry.type())
                .isEqualTo("REQUEST"));
        assertThat(store.entries()).extracting(ActivityEntryDto::id).doesNotHaveDuplicates();
    }

    private JournalActivityCapture start(Predicate<String> panelEnabled) {
        ActivityPersistenceSettings settings = new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                null,
                "app-1");
        return JournalActivityCapture.start(
                store, settings, entry -> false, journal, new JournalActivityFeed(1_000, 3, null), panelEnabled);
    }

    private void offer(String requestId, String executionId, JournalSource source, RuntimeEventPayload payload) {
        CorrelationContext context = requestId != null
                ? CorrelationContext.forRequest(requestId)
                : CorrelationContext.forExecution(executionId);
        journal.offer(RuntimeEvent.of(source, 1_000, 1_000_000, context, "t", null, false, payload));
    }

    /** An event of {@code requestId}, or an AI call when it is {@code null}, with {@code traceId} and its timing. */
    private void offerAt(String requestId, String traceId, long epochMillis, long nanos, RuntimeEventPayload payload) {
        CorrelationContext context = (requestId == null
                        ? CorrelationContext.NONE
                        : CorrelationContext.forRequest(requestId))
                .withTrace(traceId, null);
        JournalSource source = requestId == null ? JournalSource.AI : JournalSource.HTTP;
        journal.offer(RuntimeEvent.of(source, epochMillis, nanos, context, null, null, false, payload));
    }

    private static AiPayload aiPayload() {
        return new AiPayload("chat", "openai", "gpt-4o", 1L, 1L, "stop", false);
    }

    private static HttpPayload http(String path) {
        return new HttpPayload("GET", path, path, null, 200);
    }

    private static final class RecordingStore implements ActivityStore {

        private final List<List<StoredActivityEntry>> batches = new ArrayList<>();

        @Override
        public void appendBatch(List<StoredActivityEntry> entries) {
            batches.add(List.copyOf(entries));
        }

        @Override
        public ActivityPage query(ActivityQuery query) {
            throw new UnsupportedOperationException();
        }

        List<ActivityEntryDto> entries() {
            return batches.stream()
                    .flatMap(List::stream)
                    .map(StoredActivityEntry::entry)
                    .toList();
        }
    }
}
