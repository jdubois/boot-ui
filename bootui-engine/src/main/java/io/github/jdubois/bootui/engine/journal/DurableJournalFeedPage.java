package io.github.jdubois.bootui.engine.journal;

import java.util.List;

/** One newest-first page across either one durable run or the shared global archive. */
public record DurableJournalFeedPage(
        List<DurableJournalFeedItem> entries, DurableJournalGlobalCursor nextCursor, boolean hasMore) {

    public static final DurableJournalFeedPage EMPTY = new DurableJournalFeedPage(List.of(), null, false);

    public DurableJournalFeedPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public String nextCursorToken() {
        return nextCursor == null ? null : nextCursor.encode();
    }
}
