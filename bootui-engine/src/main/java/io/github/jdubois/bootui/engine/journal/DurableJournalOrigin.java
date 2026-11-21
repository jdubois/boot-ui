package io.github.jdubois.bootui.engine.journal;

import java.util.Objects;

/**
 * The durable origin of a journal row.
 *
 * @param scope tenant/application/run identity
 * @param instanceId application instance that produced the run, or {@code null} when the writer did not assign one
 */
public record DurableJournalOrigin(DurableJournalScope scope, String instanceId) {

    public DurableJournalOrigin {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        instanceId = optional("instanceId", instanceId, DurableJournalRun.MAX_INSTANCE_ID_LENGTH);
    }

    private static String optional(String field, String value, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return value;
    }
}
