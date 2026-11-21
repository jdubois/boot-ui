package io.github.jdubois.bootui.engine.journal;

/**
 * An opaque boundary for a durable event page.
 *
 * @param journalSequence the last sequence returned, within the query's run
 */
public record DurableJournalCursor(long journalSequence) {

    public DurableJournalCursor {
        if (journalSequence < 0) {
            throw new IllegalArgumentException("journalSequence must not be negative");
        }
    }

    /** A text form suitable for carrying the cursor across a process or transport boundary. */
    public String encode() {
        return Long.toString(journalSequence);
    }

    /** Decodes a cursor, returning {@code null} for a missing or invalid token. */
    public static DurableJournalCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return new DurableJournalCursor(Long.parseLong(cursor));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
