package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Pages persisted activity after applying the current panel policy, without skipping unreadable rows. */
public final class ReadableActivityPages {

    private static final int MAX_FETCHES = 8;
    private static final int FETCH_SIZE = 200;

    private ReadableActivityPages() {}

    public static ActivityPage query(ActivityStore store, ActivityQuery query, Predicate<ActivityEntryDto> readable) {
        Objects.requireNonNull(readable);
        List<StoredActivityEntry> kept = new ArrayList<>();
        String cursor = query.cursor();
        for (int i = 0; i < MAX_FETCHES && kept.size() < query.pageSize(); i++) {
            ActivityPage page = store.query(
                    query.withCursor(cursor).withPageSize(Math.min(FETCH_SIZE, query.pageSize() - kept.size())));
            if (page.entries().isEmpty()) {
                if (page.hasMore()) {
                    throw new IllegalStateException("Activity store returned an empty page with more rows");
                }
                return new ActivityPage(kept, null, false);
            }
            StoredActivityEntry last = page.entries().get(page.entries().size() - 1);
            cursor = new ActivityCursor(last.entry().timestamp(), last.seq()).encode();
            for (StoredActivityEntry entry : page.entries()) {
                if (readable.test(entry.entry())) {
                    kept.add(entry);
                }
            }
            if (!page.hasMore()) {
                return new ActivityPage(kept, null, false);
            }
        }
        return new ActivityPage(kept, cursor, true);
    }
}
