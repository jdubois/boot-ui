package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.RESERVED;
import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
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
        final List<StoredActivityEntry> allAppended = new CopyOnWriteArrayList<>();

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
        return settings(instanceId, captureInterval, 200);
    }

    private static ActivityPersistenceSettings settings(
            String instanceId, Duration captureInterval, int bufferMaxEntries) {
        return new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                bufferMaxEntries,
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
                RESERVED,
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
                RESERVED,
                () -> List.of(entry("1", "REQUEST", 1, "OK", "hi")));
        waitUntil(() -> !store.allAppended.isEmpty(), Duration.ofSeconds(2));

        poller.close();
        int countAtClose = store.allAppended.size();
        Thread.sleep(100); // well past several would-be poll cycles
        assertThat(store.allAppended).hasSize(countAtClose);
    }

    @Test
    void theStartedCoordinatorRemembersTheEntriesTheGivenRuleReserves() {
        assertThat(capturesOfAHiddenEntryThatReappears(entry -> "kept".equals(entry.id())))
                .isEqualTo(1);
        assertThat(capturesOfAHiddenEntryThatReappears(entry -> false)).isEqualTo(2);
    }

    /** Polls a feed that shows one entry, then 20 newer ones the smallest window cannot hold, then the entry again. */
    private static long capturesOfAHiddenEntryThatReappears(Predicate<ActivityEntryDto> rule) {
        RecordingStore store = new RecordingStore();
        ActivityEntryDto kept = entry("kept", "REQUEST", 1, "WARN", "slow 404");
        List<ActivityEntryDto> newer = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            newer.add(0, entry("ok-" + i, "REQUEST", i + 2, "OK", "ok"));
        }
        AtomicInteger polls = new AtomicInteger();
        try (ActivityCapturePoller poller = ActivityCaptureFactory.start(
                store,
                settings("instance-z", Duration.ofHours(1), 1),
                rule,
                () -> polls.getAndIncrement() == 1 ? newer : List.of(kept))) {
            poller.captureNow();
            poller.captureNow();
            poller.captureNow();
        }
        return store.allAppended.stream()
                .filter(stored -> "kept".equals(stored.entry().id()))
                .count();
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition was not met within " + timeout);
            }
            Thread.sleep(10);
        }
    }
}
