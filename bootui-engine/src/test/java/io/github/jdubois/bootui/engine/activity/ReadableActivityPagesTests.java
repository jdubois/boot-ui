package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ReadableActivityPagesTests {

    @Test
    void fillsPagesAcrossDisabledRowsWithoutLosingTheNextCursor() {
        InMemoryActivityStore store = new InMemoryActivityStore(100);
        for (int i = 1; i <= 10; i++) {
            store.append(new StoredActivityEntry("app", i, entry(i, i <= 3 ? "SQL" : "REQUEST")));
        }
        ActivityQuery query = ActivityQuery.firstPage("app").withPageSize(2);
        List<String> seen = new ArrayList<>();
        ActivityPage page;
        do {
            page = ReadableActivityPages.query(store, query, row -> row.type().equals("SQL"));
            seen.addAll(page.entryDtos().stream().map(ActivityEntryDto::id).toList());
            query = query.withCursor(page.nextCursor());
        } while (page.hasMore());

        assertThat(seen).containsExactly("3", "2", "1");
    }

    @Test
    void boundedScanReturnsAContinuationEvenWhenEveryFetchedRowIsHidden() {
        InMemoryActivityStore store = new InMemoryActivityStore(100);
        for (int i = 1; i <= 20; i++) {
            store.append(new StoredActivityEntry("app", i, entry(i, i == 1 ? "SQL" : "REQUEST")));
        }
        ActivityQuery query = ActivityQuery.firstPage("app").withPageSize(1);

        ActivityPage first =
                ReadableActivityPages.query(store, query, row -> row.type().equals("SQL"));
        assertThat(first.entries()).isEmpty();
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotNull();
        ActivityPage second = ReadableActivityPages.query(
                store, query.withCursor(first.nextCursor()), row -> row.type().equals("SQL"));
        assertThat(second.entries()).isEmpty();
        assertThat(second.hasMore()).isTrue();
    }

    @Test
    void aSearchNeverMatchesTextTheViewWithholds() {
        InMemoryActivityStore store = new InMemoryActivityStore(100);
        for (int i = 1; i <= 6; i++) {
            store.append(new StoredActivityEntry("app", i, entry(i, i % 2 == 0 ? "LOG" : "SQL", "secret " + i)));
        }
        UnaryOperator<ActivityEntryDto> withholdLogText = row -> row.type().equals("LOG") ? withSummary(row, "") : row;
        ActivityQuery search = new ActivityQuery("app", null, null, "SECRET", null, null, null, 2);

        ActivityPage stored = ReadableActivityPages.query(store, search, row -> true);
        assertThat(stored.entryDtos()).extracting(ActivityEntryDto::id).containsExactly("6", "5");

        List<String> seen = new ArrayList<>();
        ActivityQuery query = search;
        ActivityPage page;
        do {
            page = ReadableActivityPages.query(store, query, row -> true, withholdLogText);
            seen.addAll(page.entryDtos().stream().map(ActivityEntryDto::summary).toList());
            query = query.withCursor(page.nextCursor());
        } while (page.hasMore());
        assertThat(seen).containsExactly("secret 5", "secret 3", "secret 1");

        assertThat(ReadableActivityPages.query(
                                store, ActivityQuery.firstPage("app").withPageSize(2), row -> true, withholdLogText)
                        .entryDtos())
                .extracting(ActivityEntryDto::id, ActivityEntryDto::summary)
                .containsExactly(tuple("6", ""), tuple("5", "secret 5"));
    }

    @Test
    void durableFallbackFiltersOnlyVisibleRowsInMemoryWithoutChangingTheOverview() {
        LiveActivityReport memory = new LiveActivityReport(
                true,
                List.of(
                        entry(5, "REQUEST", "ALPHA%_"),
                        entry(3, "REQUEST", "Alpha%_"),
                        entry(2, "REQUEST", "alpha%_"),
                        entry(1, "REQUEST", "[masked]"),
                        entry(0, "REQUEST", "alphaXX")),
                Map.of("REQUEST", 8),
                null,
                List.of("Runtime journal"),
                List.of("Existing warning"));
        ActivityPersistenceOptionDto persistence = new ActivityPersistenceOptionDto(true, true, "bootui_activity");

        LiveActivityReport filtered = ReadableActivityPages.durableFallback(memory, persistence, "aLpHa%_", 1_003L);

        assertThat(filtered.entries()).extracting(ActivityEntryDto::id).containsExactly("3", "2");
        assertThat(filtered.typeCounts()).isEqualTo(memory.typeCounts());
        assertThat(filtered.sources()).isEqualTo(memory.sources());
        assertThat(filtered.warnings())
                .containsExactly(
                        "Existing warning",
                        "Durable Live Activity history is unavailable; showing the current run from memory.");
        assertThat(filtered.pageInfo()).isNull();
        assertThat(filtered.origins()).isEmpty();
        assertThat(filtered.persistenceOption()).isEqualTo(persistence);
    }

    private static ActivityEntryDto entry(int index, String type) {
        return entry(index, type, type);
    }

    private static ActivityEntryDto withSummary(ActivityEntryDto row, String summary) {
        return new ActivityEntryDto(
                row.id(),
                row.type(),
                row.timestamp(),
                row.severity(),
                summary,
                row.detail(),
                row.durationMs(),
                row.correlationId(),
                row.method(),
                row.path(),
                row.status(),
                row.thread(),
                row.profileable(),
                row.parentId(),
                row.securedPrincipal(),
                row.sqlNPlusOneSuspected());
    }

    private static ActivityEntryDto entry(int index, String type, String summary) {
        return new ActivityEntryDto(
                String.valueOf(index),
                type,
                1_000L + index,
                "OK",
                summary,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                false);
    }
}
