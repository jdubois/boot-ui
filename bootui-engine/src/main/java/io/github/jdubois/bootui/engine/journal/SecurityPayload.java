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

    @Override
    public int estimatedBytes() {
        return 16 + RuntimeEvent.stringBytes(type);
    }
}
