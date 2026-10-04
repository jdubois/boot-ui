package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.ArrayList;
import java.util.List;
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

    private static ActivityEntryDto entry(int index, String type) {
        return new ActivityEntryDto(
                String.valueOf(index),
                type,
                1_000L + index,
                "OK",
                type,
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
