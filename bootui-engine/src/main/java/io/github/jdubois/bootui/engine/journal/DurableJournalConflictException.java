package io.github.jdubois.bootui.engine.journal;

/** The same durable event key was retried with different encoded bytes. */
public final class DurableJournalConflictException extends DurableJournalStoreException {

    private final DurableJournalScope scope;
    private final long journalSequence;

    public DurableJournalConflictException(DurableJournalScope scope, long journalSequence) {
        super("Conflicting durable journal event for " + scope + " at sequence " + journalSequence);
        this.scope = scope;
        this.journalSequence = journalSequence;
    }

    public DurableJournalScope scope() {
        return scope;
    }

    public long journalSequence() {
        return journalSequence;
    }
}
