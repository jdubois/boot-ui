package io.github.jdubois.bootui.engine.activity;

import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entry;
import static io.github.jdubois.bootui.engine.activity.ActivityTestFixtures.entryWithCorrelation;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryActivityStoreTests {

    private static final String INSTANCE = "app-1";

    private void append(
            InMemoryActivityStore store, String id, String type, long timestamp, String severity, String summary) {
        store.append(new StoredActivityEntry(INSTANCE, timestamp, entry(id, type, timestamp, severity, summary)));
    }

    @Test
    void evictsOldestBeyondCapacity() {
        InMemoryActivityStore store = new InMemoryActivityStore(2);
        append(store, "1", "REQUEST", 1, "OK", "first");
        append(store, "2", "REQUEST", 2, "OK", "second");
        append(store, "3", "REQUEST", 3, "OK", "third");

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE));
        assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("3", "2");
    }

    @Test
    void queryReturnsNewestFirst() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        append(store, "1", "REQUEST", 1, "OK", "first");
        append(store, "2", "SQL", 2, "OK", "second");
        append(store, "3", "EXCEPTION", 3, "ERROR", "third");

        ActivityPage page = store.query(ActivityQuery.firstPage(INSTANCE));
        assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("3", "2", "1");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void scopesQueriesToTheRequestedInstanceOnly() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        store.append(new StoredActivityEntry("app-1", 1, entry("1", "REQUEST", 1, "OK", "from app-1")));
        store.append(new StoredActivityEntry("app-2", 1, entry("2", "REQUEST", 2, "OK", "from app-2")));

        ActivityPage page = store.query(ActivityQuery.firstPage("app-1"));
        assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("1");
    }

    @Test
    void filtersByType() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        append(store, "1", "REQUEST", 1, "OK", "req");
        append(store, "2", "SQL", 2, "OK", "sql");

        ActivityPage page = store.query(new ActivityQuery(INSTANCE, "sql", null, null, null, null, null, 200));
        assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("2");
    }

    @Test
    void filtersBySeverityCaseInsensitively() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        append(store, "1", "REQUEST", 1, "OK", "ok one");
        append(store, "2", "REQUEST", 2, "ERROR", "bad one");

        ActivityPage page = store.query(new ActivityQuery(INSTANCE, null, "error", null, null, null, null, 200));
        assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("2");
    }

    @Test
    void filtersByFreeTextAcrossSummaryAndDetail() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        store.append(new StoredActivityEntry(INSTANCE, 1, entry("1", "SQL", 1, "OK", "select users", "took 5ms")));
        store.append(new StoredActivityEntry(INSTANCE, 2, entry("2", "SQL", 2, "OK", "insert orders", "took 2ms")));

        ActivityPage page = store.query(new ActivityQuery(INSTANCE, null, null, "USERS", null, null, null, 200));
        assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("1");
    }

    @Test
    void filtersBySinceAndUntil() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        append(store, "1", "REQUEST", 100, "OK", "a");
        append(store, "2", "REQUEST", 200, "OK", "b");
        append(store, "3", "REQUEST", 300, "OK", "c");

        ActivityPage page = store.query(new ActivityQuery(INSTANCE, null, null, null, 100L, 250L, null, 200));
        assertThat(page.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("2");
    }

    @Test
    void paginatesWithCursorAcrossTwoPages() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        for (long i = 1; i <= 5; i++) {
            append(store, String.valueOf(i), "REQUEST", i, "OK", "entry " + i);
        }

        ActivityPage firstPage = store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, null, 2));
        assertThat(firstPage.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("5", "4");
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();

        ActivityPage secondPage =
                store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, firstPage.nextCursor(), 2));
        assertThat(secondPage.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("3", "2");
        assertThat(secondPage.hasMore()).isTrue();

        ActivityPage thirdPage =
                store.query(new ActivityQuery(INSTANCE, null, null, null, null, null, secondPage.nextCursor(), 2));
        assertThat(thirdPage.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("1");
        assertThat(thirdPage.hasMore()).isFalse();
        assertThat(thirdPage.nextCursor()).isNull();
    }

    @Test
    void appendBatchIgnoresNullOrEmptyInput() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        store.appendBatch(null);
        store.appendBatch(List.of());
        assertThat(store.query(ActivityQuery.firstPage(INSTANCE)).entryDtos()).isEmpty();
    }

    @Test
    void queryByCorrelationIdMatchesAcrossInstancesNewestFirst() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        store.append(new StoredActivityEntry(
                "app-1", 1, entryWithCorrelation("1", "REQUEST", 1, "OK", "entry 1", "trace-a")));
        store.append(new StoredActivityEntry(
                "app-2", 2, entryWithCorrelation("2", "REQUEST", 2, "OK", "entry 2", "trace-a")));
        // different trace, must be excluded
        store.append(new StoredActivityEntry(
                "app-1", 3, entryWithCorrelation("3", "REQUEST", 3, "OK", "entry 3", "trace-b")));

        List<StoredActivityEntry> matches = store.queryByCorrelationId("trace-a", 10);
        assertThat(matches).extracting(s -> s.entry().id()).containsExactly("2", "1");
    }

    @Test
    void queryByCorrelationIdRespectsLimit() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        store.append(new StoredActivityEntry(
                "app-1", 1, entryWithCorrelation("1", "REQUEST", 1, "OK", "entry 1", "trace-a")));
        store.append(new StoredActivityEntry(
                "app-1", 2, entryWithCorrelation("2", "REQUEST", 2, "OK", "entry 2", "trace-a")));
        store.append(new StoredActivityEntry(
                "app-1", 3, entryWithCorrelation("3", "REQUEST", 3, "OK", "entry 3", "trace-a")));

        List<StoredActivityEntry> matches = store.queryByCorrelationId("trace-a", 2);
        assertThat(matches).extracting(s -> s.entry().id()).containsExactly("3", "2");
    }

    @Test
    void queryByCorrelationIdReturnsEmptyForBlankOrUnmatchedId() {
        InMemoryActivityStore store = new InMemoryActivityStore(10);
        store.append(new StoredActivityEntry(
                "app-1", 1, entryWithCorrelation("1", "REQUEST", 1, "OK", "entry 1", "trace-a")));

        assertThat(store.queryByCorrelationId(null, 10)).isEmpty();
        assertThat(store.queryByCorrelationId("", 10)).isEmpty();
        assertThat(store.queryByCorrelationId("no-such-trace", 10)).isEmpty();
    }
}
