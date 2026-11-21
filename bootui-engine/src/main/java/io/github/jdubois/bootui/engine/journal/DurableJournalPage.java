package io.github.jdubois.bootui.engine.journal;

import java.util.List;

/**
 * One forward page of durable journal entries.
 *
 * @param entries entries in increasing original sequence order
 * @param nextCursor the exclusive boundary for the next page, or {@code null}
 * @param hasMore whether another page exists
 */
public record DurableJournalPage(List<JournalEntry> entries, DurableJournalCursor nextCursor, boolean hasMore) {

    public static final DurableJournalPage EMPTY = new DurableJournalPage(List.of(), null, false);

    public DurableJournalPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** The cursor in its transport-safe text form. */
    public String nextCursorToken() {
        return nextCursor == null ? null : nextCursor.encode();
    }
}
