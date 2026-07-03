package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ActivityCaptureFactory} wires the sequencer/coordinator/poller trio correctly and
 * starts it, rather than re-testing the trio's own behavior (already covered by {@link
 * ActivityCapturePollerTests}, {@link ActivityCaptureCoordinatorTests} and {@link
 * ActivitySequencerTests}).
 */
class ActivityCaptureFactoryTests {

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

    private static ActivityPersistenceSettings settings(String instanceId, Duration captureInterval) {
        return new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                Duration.ofDays(7),
                instanceId,
                captureInterval);
    }

    @Test
    void startReturnsAnAlreadyRunningPollerThatStampsEntriesWithTheSettingsInstanceId() throws InterruptedException {
        RecordingStore store = new RecordingStore();
        try (ActivityCapturePoller poller = ActivityCaptureFactory.start(
                store,
                settings("instance-x", Duration.ofMillis(10)),
                () -> List.of(entry("1", "REQUEST", 1, "OK", "hi")))) {
            waitUntil(() -> !store.allAppended.isEmpty(), Duration.ofSeconds(2));

            assertThat(store.allAppended).hasSize(1);
            assertThat(store.allAppended.get(0).instanceId()).isEqualTo("instance-x");
            assertThat(store.allAppended.get(0).entry().id()).isEqualTo("1");
        }
    }

    @Test
    void closingTheReturnedPollerStopsFuturePolling() throws InterruptedException {
        RecordingStore store = new RecordingStore();
        ActivityCapturePoller poller = ActivityCaptureFactory.start(
                store,
                settings("instance-y", Duration.ofMillis(10)),
                () -> List.of(entry("1", "REQUEST", 1, "OK", "hi")));
        waitUntil(() -> !store.allAppended.isEmpty(), Duration.ofSeconds(2));

        poller.close();
        int countAtClose = store.allAppended.size();
        Thread.sleep(100); // well past several would-be poll cycles
        assertThat(store.allAppended).hasSize(countAtClose);
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return;
            }
            Thread.sleep(10);
        }
    }
}
