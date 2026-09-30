package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.RESERVED;
import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.request;
import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.restCall;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.web.ReservedActivityEntries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ActivityCaptureCoordinatorTests {

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

    @Test
    void capturesAllEntriesOnFirstIngestOldestFirst() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100, RESERVED);

        // Merged feeds are newest-first, as LiveActivityService.report(...) returns them.
        coordinator.ingest(List.of(
                entry("3", "REQUEST", 3, "OK", "c"),
                entry("2", "REQUEST", 2, "OK", "b"),
                entry("1", "REQUEST", 1, "OK", "a")));

        assertThat(store.allAppended).extracting(e -> e.entry().id()).containsExactly("1", "2", "3");
        assertThat(store.allAppended).extracting(StoredActivityEntry::seq).containsExactly(1L, 2L, 3L);
    }

    @Test
    void secondIngestOnlyCapturesEntriesNotSeenBefore() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100, RESERVED);

        coordinator.ingest(List.of(entry("2", "REQUEST", 2, "OK", "b"), entry("1", "REQUEST", 1, "OK", "a")));
        coordinator.ingest(List.of(
                entry("3", "REQUEST", 3, "OK", "c"),
                entry("2", "REQUEST", 2, "OK", "b"),
                entry("1", "REQUEST", 1, "OK", "a")));

        assertThat(store.allAppended).extracting(e -> e.entry().id()).containsExactly("1", "2", "3");
    }

    @Test
    void neverCapturesEntriesWithNullId() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100, RESERVED);

        coordinator.ingest(List.of(entry(null, "SECURITY", 1, "OK", "no id")));
        coordinator.ingest(List.of(entry(null, "SECURITY", 1, "OK", "no id")));

        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void ingestWithNullOrEmptyListIsANoOp() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100, RESERVED);

        coordinator.ingest(null);
        coordinator.ingest(List.of());

        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void reCapturesAnIdEvictedFromTheBoundedSeenSetUnderExtremeBurst() {
        RecordingStore store = new RecordingStore();
        // Capacity clamps to a minimum of 16; use exactly that to make eviction deterministic and fast.
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 1, RESERVED);

        coordinator.ingest(List.of(entry("first", "REQUEST", 1, "OK", "a")));
        List<ActivityEntryDto> flood = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            flood.add(entry("flood-" + i, "REQUEST", i + 2, "OK", "flood"));
        }
        coordinator.ingest(flood);

        // "first" has been evicted from the bounded seen-set, so it is treated as new again if it
        // reappears in a later poll's window (the documented lossy-under-extreme-burst trade-off).
        coordinator.ingest(List.of(entry("first", "REQUEST", 1, "OK", "a")));

        long firstCaptureCount = store.allAppended.stream()
                .filter(e -> "first".equals(e.entry().id()))
                .count();
        assertThat(firstCaptureCount).isEqualTo(2);
    }

    @Test
    void neverReCapturesAnEntryThatStaysInTheViewWhileNewerEntriesFlood() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 1, RESERVED);
        // A failure held in a failure-preserving buffer's reserved share outlives many routine entries.
        ActivityEntryDto failure = request("failure", 1, "ERROR", 500, 12L);

        coordinator.ingest(List.of(failure));
        for (int poll = 0; poll < 5; poll++) {
            List<ActivityEntryDto> view = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int id = poll * 20 + i;
                view.add(0, entry("ok-" + id, "REQUEST", id + 2, "OK", "ok"));
            }
            view.add(failure);
            coordinator.ingest(view);
        }

        assertThat(captures(store, "failure")).isEqualTo(1);
        assertThat(store.allAppended).hasSize(1 + 5 * 20);
    }

    @Test
    void neverReCapturesAFailureThatNewerRoutineEntriesHidFromACappedView() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 1, RESERVED);
        ActivityEntryDto failure = request("failure", 1, "ERROR", 500, 12L);

        coordinator.ingest(List.of(failure));
        for (int poll = 0; poll < 5; poll++) {
            List<ActivityEntryDto> view = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int id = poll * 20 + i;
                view.add(0, entry("sql-" + id, "SQL", id + 2, "OK", "select"));
            }
            // The capped view no longer reaches the failure, which its source still retains.
            coordinator.ingest(view);
        }
        // The newer entries are cleared, so the retained failure is visible again.
        coordinator.ingest(List.of(failure));

        assertThat(captures(store, "failure")).isEqualTo(1);
    }

    @Test
    void routineWarningsDoNotEvictAReservedFailureFromTheReservedWindow() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 1, RESERVED);
        ActivityEntryDto failure = request("failure", 1, "ERROR", 500, 12L);

        coordinator.ingest(List.of(failure));
        for (int i = 0; i < 40; i++) {
            // Fast 4xx exchanges are WARN in the stream but routine in the exchange buffer.
            coordinator.ingest(List.of(request("not-found-" + i, i + 2, "WARN", 404, 5L)));
        }
        coordinator.ingest(List.of(failure));

        assertThat(captures(store, "failure")).isEqualTo(1);
    }

    @Test
    void neverReCapturesASlowClientErrorThatRoutineTrafficHidFromACappedView() {
        // The exchange buffer reserves a 404 that took 1.5 s, although the stream shows it as WARN, not SLOW.
        ActivityEntryDto slowNotFound = request("slow-404", 1, "WARN", 404, 1_500L);

        assertThat(capturesAfterHidingAndReappearing(slowNotFound, RESERVED)).isEqualTo(1);
    }

    @Test
    void aSeverityOnlyRuleCapturesAHiddenSlowClientErrorTwice() {
        // The rule persistence applied before it followed the exchange buffer: ERROR, SLOW, and REST client WARN.
        Predicate<ActivityEntryDto> severityOnly = entry -> "ERROR".equals(entry.severity())
                || "SLOW".equals(entry.severity())
                || ("WARN".equals(entry.severity()) && "REST_CLIENT".equals(entry.type()));
        ActivityEntryDto slowNotFound = request("slow-404", 1, "WARN", 404, 1_500L);

        assertThat(capturesAfterHidingAndReappearing(slowNotFound, severityOnly))
                .as("the buffer keeps this record, so it can reappear after the first window forgot it")
                .isEqualTo(2);
    }

    /**
     * Every combination of the rules the failure-preserving buffers apply, as each adapter renders the entry: a record
     * its buffer reserves is captured once even when it reappears after the first window forgot it; any other record
     * is captured again, the documented trade-off for records their buffer evicts oldest first.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reappearingEntries")
    void capturesAReappearingEntryOnceExactlyWhenItsBufferReservesIt(
            String description, ActivityEntryDto entry, long requestSlowThresholdMillis, int expectedCaptures) {
        assertThat(capturesAfterHidingAndReappearing(entry, new ReservedActivityEntries(requestSlowThresholdMillis)))
                .isEqualTo(expectedCaptures);
    }

    static Stream<Arguments> reappearingEntries() {
        return Stream.of(
                Arguments.of("slow 404 request", request("r", 1, "WARN", 404, 1_500L), 1_000L, 1),
                Arguments.of("404 request at the threshold", request("r", 1, "WARN", 404, 1_000L), 1_000L, 1),
                Arguments.of("404 request below the threshold", request("r", 1, "WARN", 404, 999L), 1_000L, 2),
                Arguments.of("404 request without a duration", request("r", 1, "WARN", 404, null), 1_000L, 2),
                Arguments.of("slow 404 request, slow threshold 0", request("r", 1, "WARN", 404, 1_500L), 0L, 2),
                Arguments.of("slow 404 request, raised threshold", request("r", 1, "WARN", 404, 1_500L), 2_000L, 2),
                Arguments.of("slow 503 request", request("r", 1, "ERROR", 503, 1_500L), 1_000L, 1),
                Arguments.of("fast 500 request", request("r", 1, "ERROR", 500, 3L), 1_000L, 1),
                Arguments.of("500 request without a duration", request("r", 1, "ERROR", 500, null), 1_000L, 1),
                Arguments.of("500 request, slow threshold 0", request("r", 1, "ERROR", 500, 1_500L), 0L, 1),
                Arguments.of("slow 200 request", request("r", 1, "SLOW", 200, 1_000L), 1_000L, 1),
                Arguments.of("slow 302 request", request("r", 1, "SLOW", 302, 4_000L), 1_000L, 1),
                Arguments.of("fast 200 request", request("r", 1, "OK", 200, 12L), 1_000L, 2),
                Arguments.of("slow 200 request, slow threshold 0", request("r", 1, "OK", 200, 1_500L), 0L, 2),
                Arguments.of("slow request without a response", request("r", 1, "SLOW", 0, 1_500L), 1_000L, 1),
                Arguments.of("slow 404 REST call", restCall("r", 1, "WARN", 404, 1_500L), 1_000L, 1),
                Arguments.of("fast 404 REST call", restCall("r", 1, "WARN", 404, 5L), 1_000L, 1),
                Arguments.of("slow 503 REST call", restCall("r", 1, "ERROR", 503, 1_500L), 1_000L, 1),
                Arguments.of("failed REST call", restCall("r", 1, "ERROR", null, 30L), 1_000L, 1),
                Arguments.of("slow failed REST call", restCall("r", 1, "ERROR", null, 1_500L), 0L, 1),
                Arguments.of("slow 200 REST call", restCall("r", 1, "SLOW", 200, 1_500L), 0L, 1),
                Arguments.of("fast 200 REST call", restCall("r", 1, "OK", 200, 5L), 1_000L, 2),
                Arguments.of("slow failed SQL", entry("r", "SQL", 1, "ERROR", "select"), 1_000L, 1),
                Arguments.of("slow SQL", entry("r", "SQL", 1, "SLOW", "select"), 0L, 1),
                Arguments.of("routine SQL", entry("r", "SQL", 1, "OK", "select"), 1_000L, 2),
                Arguments.of("exception", entry("r", "EXCEPTION", 1, "ERROR", "boom"), 1_000L, 2),
                Arguments.of("failed scheduled run", entry("r", "SCHEDULED", 1, "ERROR", "job"), 1_000L, 2),
                Arguments.of("slow scheduled run", entry("r", "SCHEDULED", 1, "SLOW", "job"), 1_000L, 2),
                Arguments.of("failed message", entry("r", "MESSAGING", 1, "ERROR", "orders"), 1_000L, 2),
                Arguments.of("denied security event", entry("r", "SECURITY", 1, "WARN", "denied"), 1_000L, 2),
                Arguments.of("trapped mail", entry("r", "MAIL", 1, "WARN", "hello"), 1_000L, 2),
                Arguments.of("cache miss", entry("r", "CACHE", 1, "WARN", "miss"), 1_000L, 2),
                Arguments.of("open circuit", entry("r", "FAULT_TOLERANCE", 1, "ERROR", "open"), 1_000L, 2));
    }

    @Test
    void failuresFromBuffersWithoutAReservedShareDoNotEvictAReservedRecordFromTheReservedWindow() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 1, RESERVED);
        ActivityEntryDto slowNotFound = request("slow-404", 1, "WARN", 404, 1_500L);

        coordinator.ingest(List.of(slowNotFound));
        for (int i = 0; i < 40; i++) {
            // A failing @Scheduled job: ERROR entries from a run store that evicts strictly oldest first.
            coordinator.ingest(List.of(entry("sched-" + i, "SCHEDULED", i + 2, "ERROR", "job failed")));
        }
        coordinator.ingest(List.of(slowNotFound));

        assertThat(captures(store, "slow-404")).isEqualTo(1);
        assertThat(coordinator.reservedSeenCount()).isEqualTo(1);
    }

    @Test
    void theReservedWindowForgetsItsOldestRecordOnceMoreReservedRecordsThanItHoldsArrive() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 1, RESERVED);
        ActivityEntryDto slowNotFound = request("slow-404", 1, "WARN", 404, 1_500L);

        coordinator.ingest(List.of(slowNotFound));
        for (int i = 0; i < 16; i++) {
            coordinator.ingest(List.of(request("failure-" + i, i + 2, "ERROR", 500, 3L)));
        }
        coordinator.ingest(List.of(slowNotFound));

        assertThat(captures(store, "slow-404"))
                .as("the documented bound of the reserved window")
                .isEqualTo(2);
    }

    @Test
    void bothWindowsStayBoundedByTheirCapacityPlusTheCurrentView() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 1, RESERVED);
        int viewSize = 10;
        for (int poll = 0; poll < 100; poll++) {
            List<ActivityEntryDto> view = new ArrayList<>();
            for (int i = 0; i < viewSize; i++) {
                int id = poll * viewSize + i;
                view.add(0, request("req-" + id, id, "WARN", 404, 1_500L));
            }
            coordinator.ingest(view);

            assertThat(coordinator.seenCount()).isLessThanOrEqualTo(16 + viewSize);
            assertThat(coordinator.reservedSeenCount()).isLessThanOrEqualTo(16 + viewSize);
        }
        assertThat(store.allAppended).hasSize(100 * viewSize);
        assertThat(coordinator.reservedSeenCount()).isEqualTo(16);
    }

    /**
     * Captures {@code entry}, hides it behind five polls of 20 newer routine entries, more than the smallest first
     * window holds, then shows it again, as a capped view does once newer entries are cleared from their buffers.
     */
    private static long capturesAfterHidingAndReappearing(ActivityEntryDto entry, Predicate<ActivityEntryDto> rule) {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 1, rule);

        coordinator.ingest(List.of(entry));
        for (int poll = 0; poll < 5; poll++) {
            List<ActivityEntryDto> view = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int id = poll * 20 + i;
                view.add(0, request("ok-" + id, id + 2, "OK", 200, 4L));
            }
            coordinator.ingest(view);
        }
        coordinator.ingest(List.of(entry));

        return captures(store, entry.id());
    }

    private static long captures(RecordingStore store, String id) {
        return store.allAppended.stream().filter(e -> id.equals(e.entry().id())).count();
    }
}
