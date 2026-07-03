package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);

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
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);

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
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);

        coordinator.ingest(List.of(entry(null, "SECURITY", 1, "OK", "no id")));
        coordinator.ingest(List.of(entry(null, "SECURITY", 1, "OK", "no id")));

        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void ingestWithNullOrEmptyListIsANoOp() {
        RecordingStore store = new RecordingStore();
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 100);

        coordinator.ingest(null);
        coordinator.ingest(List.of());

        assertThat(store.allAppended).isEmpty();
    }

    @Test
    void reCapturesAnIdEvictedFromTheBoundedSeenSetUnderExtremeBurst() {
        RecordingStore store = new RecordingStore();
        // Capacity clamps to a minimum of 16; use exactly that to make eviction deterministic and fast.
        ActivityCaptureCoordinator coordinator =
                new ActivityCaptureCoordinator(store, new ActivitySequencer("app-1"), 1);

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
}
