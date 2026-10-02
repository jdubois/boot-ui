package io.github.jdubois.bootui.engine.journal;

import java.util.Objects;

/**
 * An application event published, or one listener's run for it ({@code docs/PLAN-v2.md} §5.18, M4-8): the event's type
 * and the listener, never the event's fields or its {@code toString()}. A transactional listener records where its
 * transaction placed it: deferred to a phase, run then, or skipped because no transaction was active.
 *
 * @param kind {@link #PUBLISHED} or {@link #LISTENER}
 * @param eventType the event's class, or its payload's for a payload event
 * @param listener the listener, such as {@code OrderListener#onPlaced}, or {@code null} for a publication
 * @param phase {@code IMMEDIATE}, or a transaction phase such as {@code AFTER_COMMIT}, or {@code ASYNC}
 * @param outcome {@link #RAN}, {@link #FAILED}, {@link #DEFERRED}, or {@link #SKIPPED_NO_TRANSACTION}; {@code null} for
 *     a publication
 * @param exceptionClass the exception a failed listener threw, or {@code null}
 * @param listeners how many listeners a publication reached
 * @param startNanos the {@link System#nanoTime()} when the listener started, or {@code -1}, which places the statements
 *     it ran below the millisecond
 */
public record AppEventPayload(
        String kind,
        String eventType,
        String listener,
        String phase,
        String outcome,
        String exceptionClass,
        int listeners,
        long startNanos)
        implements RuntimeEventPayload {

    public static final String PUBLISHED = "PUBLISHED";
    public static final String LISTENER = "LISTENER";

    public static final String IMMEDIATE = "IMMEDIATE";

    public static final String RAN = "RAN";
    public static final String FAILED = "FAILED";
    public static final String DEFERRED = "DEFERRED";
    public static final String SKIPPED_NO_TRANSACTION = "SKIPPED_NO_TRANSACTION";

    public AppEventPayload {
        Objects.requireNonNull(kind, "kind");
    }

    /** A publication that reached {@code listeners} listeners. */
    public static AppEventPayload published(String eventType, int listeners) {
        return new AppEventPayload(PUBLISHED, eventType, null, null, null, null, listeners, -1);
    }

    /** One listener's run, or why it did not run. */
    public static AppEventPayload listener(
            String eventType, String listener, String phase, String outcome, String exceptionClass, long startNanos) {
        return new AppEventPayload(LISTENER, eventType, listener, phase, outcome, exceptionClass, 0, startNanos);
    }

    /** Whether it is a listener that ran after its transaction completed, committed or not. */
    public boolean afterCompletion() {
        return LISTENER.equals(kind)
                && ("AFTER_COMMIT".equals(phase) || "AFTER_COMPLETION".equals(phase) || "AFTER_ROLLBACK".equals(phase))
                && (RAN.equals(outcome) || FAILED.equals(outcome));
    }

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new AppEventPayload(
                dictionary.shared(kind),
                dictionary.shared(eventType),
                dictionary.shared(listener),
                dictionary.shared(phase),
                dictionary.shared(outcome),
                dictionary.shared(exceptionClass),
                listeners,
                startNanos);
    }

    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 40
                + JournalDictionary.retained(dictionary, kind)
                + JournalDictionary.retained(dictionary, eventType)
                + JournalDictionary.retained(dictionary, listener)
                + JournalDictionary.retained(dictionary, phase)
                + JournalDictionary.retained(dictionary, outcome)
                + JournalDictionary.retained(dictionary, exceptionClass);
    }
}
