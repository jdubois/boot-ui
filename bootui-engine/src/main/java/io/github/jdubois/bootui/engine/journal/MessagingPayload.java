package io.github.jdubois.bootui.engine.journal;

/**
 * A message's payload: the broker ({@code kafka}, {@code rabbitmq}, or {@code jms}), whether it was sent or consumed,
 * its destination (topic, exchange, or queue), whether it failed, and, for a consumed message whose {@code traceparent}
 * named one, the trace that sent it. Never its key, headers, or body.
 */
public record MessagingPayload(String broker, boolean sent, String destination, boolean failed, String linkedTraceId)
        implements RuntimeEventPayload {

    /** A message without a trace link. */
    public MessagingPayload(String broker, boolean sent, String destination, boolean failed) {
        this(broker, sent, destination, failed, null);
    }

    /** This message with its broker and destination replaced by the run's shared copies. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new MessagingPayload(
                dictionary.shared(broker), sent, dictionary.shared(destination), failed, linkedTraceId);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 16
                + JournalDictionary.retained(dictionary, broker)
                + JournalDictionary.retained(dictionary, destination)
                + JournalDictionary.retained(dictionary, linkedTraceId);
    }
}
