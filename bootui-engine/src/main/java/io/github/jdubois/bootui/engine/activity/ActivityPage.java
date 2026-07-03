package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.List;

/**
 * One page of {@link ActivityStore#query(ActivityQuery)} results: entries newest-first, plus whether
 * more (older) entries exist and the cursor to fetch them.
 *
 * @param entries the page of entries, newest-first, each carrying its {@code (instanceId, seq)} identity
 * @param nextCursor {@link ActivityCursor#encode() encoded cursor} to pass as {@link
 *     ActivityQuery#cursor()} to fetch the next (older) page, or {@code null} when {@code entries} is
 *     the oldest available page
 * @param hasMore whether older entries exist beyond this page
 */
public record ActivityPage(List<StoredActivityEntry> entries, String nextCursor, boolean hasMore) {

    public ActivityPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static final ActivityPage EMPTY = new ActivityPage(List.of(), null, false);

    /** The page's entries as plain wire DTOs, dropping the {@code (instanceId, seq)} bookkeeping. */
    public List<ActivityEntryDto> entryDtos() {
        return entries.stream().map(StoredActivityEntry::entry).toList();
    }
}
