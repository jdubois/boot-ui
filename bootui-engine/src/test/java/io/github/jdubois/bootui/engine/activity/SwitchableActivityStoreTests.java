package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SwitchableActivityStoreTests {

    @Test
    void delegatesEveryOperationToTheCurrentDelegate() {
        InMemoryActivityStore inMemory = new InMemoryActivityStore(10);
        SwitchableActivityStore store = new SwitchableActivityStore(inMemory);

        store.appendBatch(List.of(new StoredActivityEntry("app-1", 1, entry("1", "REQUEST", 1, "OK", "hello"))));

        assertThat(store.query(ActivityQuery.firstPage("app-1")).entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("1");
    }

    @Test
    void pruneAndCloseReachTheDelegateRatherThanTheInterfaceDefaultNoOp() {
        AtomicBoolean pruned = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        ActivityStore recording = new ActivityStore() {
            @Override
            public void appendBatch(List<StoredActivityEntry> entries) {}

            @Override
            public ActivityPage query(ActivityQuery query) {
                return ActivityPage.EMPTY;
            }

            @Override
            public void prune(String instanceId, long olderThanEpochMillis) {
                pruned.set(true);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        SwitchableActivityStore store = new SwitchableActivityStore(recording);
        store.prune("app-1", 0);
        store.close();

        assertThat(pruned).isTrue();
        assertThat(closed).isTrue();
    }

    @Test
    void isNotPersistentWhenDelegateIsInMemory() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(10));
        assertThat(store.persistent()).isFalse();
    }

    @Test
    void isPersistentWhenDelegateIsBuffered() {
        SwitchableActivityStore store = new SwitchableActivityStore(fakeBufferedStore());
        assertThat(store.persistent()).isTrue();
    }

    @Test
    void attemptSwitchToPersistentSucceedsOnceAndSwapsTheDelegate() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(10));
        BufferedActivityStore replacement = fakeBufferedStore();

        boolean switched = store.attemptSwitchToPersistent(replacement);

        assertThat(switched).isTrue();
        assertThat(store.persistent()).isTrue();
        assertThat(store.delegate()).isSameAs(replacement);
    }

    @Test
    void attemptSwitchToPersistentIsANoOpWhenAlreadyPersistent() {
        BufferedActivityStore first = fakeBufferedStore();
        SwitchableActivityStore store = new SwitchableActivityStore(first);
        BufferedActivityStore second = fakeBufferedStore();

        boolean switched = store.attemptSwitchToPersistent(second);

        assertThat(switched).isFalse();
        assertThat(store.delegate()).isSameAs(first);
        second.close();
    }

    private static BufferedActivityStore fakeBufferedStore() {
        ActivityStore durable = new ActivityStore() {
            @Override
            public void appendBatch(List<StoredActivityEntry> entries) {}

            @Override
            public ActivityPage query(ActivityQuery query) {
                return ActivityPage.EMPTY;
            }
        };
        return new BufferedActivityStore(
                new InMemoryActivityStore(10), durable, Duration.ofSeconds(30), 10, "app-1", null);
    }
}
