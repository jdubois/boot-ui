package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entryWithCorrelation;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.RemoteActivityEntryDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteActivityCorrelatorTests {

    /** Records the last {@code queryByCorrelationId} call it received and returns a canned result. */
    private static final class FakeStore implements ActivityStore {
        String lastCorrelationId;
        int lastLimit = -1;
        List<StoredActivityEntry> toReturn = List.of();

        @Override
        public void appendBatch(List<StoredActivityEntry> entries) {}

        @Override
        public ActivityPage query(ActivityQuery query) {
            return ActivityPage.EMPTY;
        }

        @Override
        public List<StoredActivityEntry> queryByCorrelationId(String correlationId, int limit) {
            this.lastCorrelationId = correlationId;
            this.lastLimit = limit;
            return toReturn;
        }
    }

    private static StoredActivityEntry stored(String instanceId, long seq, long timestamp, String id) {
        return new StoredActivityEntry(
                instanceId, seq, entryWithCorrelation(id, "REQUEST", timestamp, "OK", "entry " + id, "trace-a"));
    }

    @Test
    void returnsEmptyListWhenStoreIsNull() {
        assertThat(RemoteActivityCorrelator.forRequest(null, "trace-a", "app-1"))
                .isEmpty();
    }

    @Test
    void returnsEmptyListWhenCorrelationIdIsNullOrBlank() {
        FakeStore store = new FakeStore();

        assertThat(RemoteActivityCorrelator.forRequest(store, null, "app-1")).isEmpty();
        assertThat(RemoteActivityCorrelator.forRequest(store, "", "app-1")).isEmpty();
        assertThat(RemoteActivityCorrelator.forRequest(store, "   ", "app-1")).isEmpty();
        // Short-circuits before ever reaching the store: no query should have been attempted.
        assertThat(store.lastCorrelationId).isNull();
        assertThat(store.lastLimit).isEqualTo(-1);
    }

    @Test
    void returnsEmptyListWhenStoreHasNoMatchingRows() {
        FakeStore store = new FakeStore();
        store.toReturn = List.of();

        assertThat(RemoteActivityCorrelator.forRequest(store, "trace-a", "app-1"))
                .isEmpty();
    }

    @Test
    void excludesEntriesFromOwnInstance() {
        FakeStore store = new FakeStore();
        store.toReturn = List.of(stored("app-1", 1, 100, "own"), stored("app-2", 1, 200, "remote"));

        List<RemoteActivityEntryDto> result = RemoteActivityCorrelator.forRequest(store, "trace-a", "app-1");

        assertThat(result).extracting(r -> r.entry().id()).containsExactly("remote");
        assertThat(result).extracting(RemoteActivityEntryDto::instanceId).containsExactly("app-2");
    }

    @Test
    void keepsEntriesFromAllOtherInstancesWhenOwnInstanceIdIsNull() {
        FakeStore store = new FakeStore();
        store.toReturn = List.of(stored("app-2", 1, 100, "a"), stored("app-3", 1, 200, "b"));

        List<RemoteActivityEntryDto> result = RemoteActivityCorrelator.forRequest(store, "trace-a", null);

        assertThat(result).extracting(r -> r.entry().id()).containsExactly("a", "b");
    }

    @Test
    void clearsParentIdOnEveryReturnedEntryButKeepsOtherFieldsIntact() {
        FakeStore store = new FakeStore();
        ActivityEntryDto withParent = new ActivityEntryDto(
                "remote-1",
                "SQL",
                100L,
                "OK",
                "select 1",
                "took 5ms",
                5L,
                "trace-a",
                null,
                null,
                null,
                null,
                false,
                "local-parent-request-id",
                "alice",
                false);
        store.toReturn = List.of(new StoredActivityEntry("app-2", 1, withParent));

        List<RemoteActivityEntryDto> result = RemoteActivityCorrelator.forRequest(store, "trace-a", "app-1");

        assertThat(result).hasSize(1);
        ActivityEntryDto returned = result.get(0).entry();
        assertThat(returned.parentId()).isNull();
        // Everything else about the entry is passed through unchanged.
        assertThat(returned.id()).isEqualTo("remote-1");
        assertThat(returned.summary()).isEqualTo("select 1");
        assertThat(returned.securedPrincipal()).isEqualTo("alice");
        assertThat(returned.correlationId()).isEqualTo("trace-a");
    }

    @Test
    void sortsResultsOldestFirstByTimestampRegardlessOfStoreOrder() {
        FakeStore store = new FakeStore();
        store.toReturn = List.of(
                stored("app-2", 1, 300, "third"), stored("app-2", 2, 100, "first"), stored("app-2", 3, 200, "second"));

        List<RemoteActivityEntryDto> result = RemoteActivityCorrelator.forRequest(store, "trace-a", "app-1");

        assertThat(result).extracting(r -> r.entry().id()).containsExactly("first", "second", "third");
    }

    @Test
    void passesTheMaxRemoteEntriesCapAndTheCorrelationIdThroughToTheStore() {
        FakeStore store = new FakeStore();

        RemoteActivityCorrelator.forRequest(store, "trace-a", "app-1");

        assertThat(store.lastCorrelationId).isEqualTo("trace-a");
        assertThat(store.lastLimit).isEqualTo(RemoteActivityCorrelator.MAX_REMOTE_ENTRIES);
    }
}
