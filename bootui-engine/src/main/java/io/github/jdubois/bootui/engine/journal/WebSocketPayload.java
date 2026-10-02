package io.github.jdubois.bootui.engine.journal;

/**
 * A WebSocket message's payload ({@code docs/PLAN-v2.md} §5.18): the endpoint it arrived on, what happened
 * ({@link #MESSAGE} for an application message a handler ran), whether it was inbound, its destination as a template
 * such as {@code /app/chat/{room}} or, without STOMP, the endpoint's path, its size in bytes when the framework knows
 * it without reading it ({@code -1} otherwise), the close status of a closed session, and whether its handler failed.
 * Never its body, headers, principal, or session id.
 *
 * <p>An inbound {@link #MESSAGE} opens an execution of its own, as a consumed message does, so the SQL, exceptions,
 * and calls its handler makes nest under it and request-level observations cover it.</p>
 */
public record WebSocketPayload(
        String endpoint,
        String kind,
        boolean inbound,
        String destination,
        long payloadBytes,
        String closeStatus,
        boolean failed)
        implements RuntimeEventPayload {

    /** A session opened. */
    public static final String OPEN = "OPEN";
    /** A session closed. */
    public static final String CLOSE = "CLOSE";
    /** An application message, handled by the application when inbound. */
    public static final String MESSAGE = "MESSAGE";

    /** An inbound application message a handler ran, which opens an execution. */
    public static WebSocketPayload handled(String endpoint, String destination, Long payloadBytes, boolean failed) {
        return new WebSocketPayload(
                endpoint, MESSAGE, true, destination, payloadBytes == null ? -1 : payloadBytes, null, failed);
    }

    /** Whether this is an inbound application message, the entry of an execution. */
    public boolean opensExecution() {
        return inbound && MESSAGE.equals(kind);
    }

    /** This message with its strings replaced by the run's shared copies. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new WebSocketPayload(
                dictionary.shared(endpoint),
                dictionary.shared(kind),
                inbound,
                dictionary.shared(destination),
                payloadBytes,
                dictionary.shared(closeStatus),
                failed);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 24
                + JournalDictionary.retained(dictionary, endpoint)
                + JournalDictionary.retained(dictionary, kind)
                + JournalDictionary.retained(dictionary, destination)
                + JournalDictionary.retained(dictionary, closeStatus);
    }
}
