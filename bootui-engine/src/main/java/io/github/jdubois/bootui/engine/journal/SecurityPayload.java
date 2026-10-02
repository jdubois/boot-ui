package io.github.jdubois.bootui.engine.journal;

import java.util.Locale;

/**
 * A security event's payload: its type, such as {@code AUTHENTICATION_SUCCESS}. Never the principal, which the journal
 * does not keep, nor the event's details.
 */
public record SecurityPayload(String type) implements RuntimeEventPayload {

    /** Whether the type names a failure or a denial, which the journal retains longer. */
    public static boolean isFailure(String type) {
        if (type == null) {
            return false;
        }
        String upper = type.toUpperCase(Locale.ROOT);
        return upper.contains("FAILURE") || upper.contains("DENIED");
    }

    /** This event with its type replaced by the run's shared copy. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new SecurityPayload(dictionary.shared(type));
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 16 + JournalDictionary.retained(dictionary, type);
    }
}
