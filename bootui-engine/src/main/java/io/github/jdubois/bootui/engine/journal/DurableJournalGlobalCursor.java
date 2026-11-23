package io.github.jdubois.bootui.engine.journal;

/**
 * Stable tuple ordering for a future cross-run archive reader.
 *
 * <p>The store assigns {@code globalSequence} under an atomic, transactional counter update in its runs table. This
 * avoids clock-skew and equal-timestamp ambiguity across concurrent writers.
 */
public record DurableJournalGlobalCursor(long globalSequence) {

    public DurableJournalGlobalCursor {
        if (globalSequence < 0) {
            throw new IllegalArgumentException("globalSequence must not be negative");
        }
    }

    public String encode() {
        return Long.toString(globalSequence);
    }

    public static DurableJournalGlobalCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return new DurableJournalGlobalCursor(Long.parseLong(cursor));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Accepts an absent cursor but rejects a supplied token that the legacy decoder cannot parse. */
    public static DurableJournalGlobalCursor decodeStrict(String cursor) {
        DurableJournalGlobalCursor decoded = decode(cursor);
        if (decoded == null && cursor != null && !cursor.isBlank()) {
            throw new IllegalArgumentException("Invalid durable journal cursor");
        }
        return decoded;
    }
}
