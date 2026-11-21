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
}
