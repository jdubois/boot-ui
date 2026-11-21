package io.github.jdubois.bootui.engine.journal;

/** A durable journal operation could not be completed. */
public class DurableJournalStoreException extends RuntimeException {

    public DurableJournalStoreException(String message) {
        super(message);
    }

    public DurableJournalStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
