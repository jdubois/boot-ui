package io.github.jdubois.bootui.engine.journal;

/** A historical durable journal read could not be served reliably. */
public class DurableJournalUnavailableException extends DurableJournalStoreException {

    public DurableJournalUnavailableException(String message) {
        super(message);
    }

    public DurableJournalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
