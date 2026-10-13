package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class LowFootprintActivityStoreTests {

    private static final String INSTANCE = "app-1";

    private static final class RecordingDurableStore implements ActivityStore {
        final List<StoredActivityEntry> accepted = new CopyOnWriteArrayList<>();

        @Override
        public void appendBatch(List<StoredActivityEntry> entries) {
            accepted.addAll(entries);
        }

        @Override
        public ActivityPage query(ActivityQuery query) {
            return new ActivityPage(new ArrayList<>(accepted), null, false);
        }
    }

    private static StoredActivityEntry stamped(long seq) {
        return new StoredActivityEntry(INSTANCE, seq, entry("e" + seq, "REQUEST", seq, "OK", "entry " + seq));
    }

    @Test
    void anInMemoryStoreSkipsEntriesWhilePausedAndRecordsAgainOnResume() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(10));
        store.append(stamped(1));

        store.suspendForLowFootprint();
        store.append(stamped(2));
        assertThat(store.query(ActivityQuery.firstPage(INSTANCE)).entryDtos()).isEmpty();

        store.resumeFromLowFootprint();
        store.append(stamped(3));
        assertThat(store.query(ActivityQuery.firstPage(INSTANCE)).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("e3");
    }

    @Test
    void aDurableStoreFlushesPendingEntriesOnceBeforeItPauses() {
        RecordingDurableStore durable = new RecordingDurableStore();
        try (BufferedActivityStore buffered =
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, Duration.ofSeconds(60), 100)) {
            SwitchableActivityStore store = new SwitchableActivityStore(buffered);
            store.append(stamped(1));
            assertThat(durable.accepted).isEmpty();

            store.suspendForLowFootprint();

            assertThat(durable.accepted).extracting(e -> e.entry().id()).containsExactly("e1");
            store.append(stamped(2));
            assertThat(buffered.pendingCount()).isZero();
            assertThat(store.persistent()).isTrue();

            store.resumeFromLowFootprint();
            store.append(stamped(3));
            assertThat(buffered.pendingCount()).isEqualTo(1);
        }
    }
}
