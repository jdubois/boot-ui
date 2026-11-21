package io.github.jdubois.bootui.engine.journal;

/** Rows deleted by one durable journal clear or retention operation. */
public record DurableJournalDeletionCounts(long events, long details, long runs) {

    public DurableJournalDeletionCounts {
        if (events < 0 || details < 0 || runs < 0) {
            throw new IllegalArgumentException("deleted row counts must not be negative");
        }
    }
}
