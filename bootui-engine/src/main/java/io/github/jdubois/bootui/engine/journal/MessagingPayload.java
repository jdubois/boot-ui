package io.github.jdubois.bootui.engine.journal;

import java.util.regex.Pattern;

/**
 * A message's payload: the broker ({@code kafka}, {@code rabbitmq}, or {@code jms}), whether it was sent or consumed,
 * its destination (topic, exchange, or queue), whether it failed, and, for a consumed message whose {@code traceparent}
 * named one, the trace that sent it. Never its key, headers, or body.
 */
public record MessagingPayload(String broker, boolean sent, String destination, boolean failed, String linkedTraceId)
        implements RuntimeEventPayload {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** A message without a trace link. */
    public MessagingPayload(String broker, boolean sent, String destination, boolean failed) {
        this(broker, sent, destination, failed, null);
    }

    /**
     * This message with its broker and destination replaced by the run's shared copies. A temporary destination, such
     * as a reply queue the broker names per request, keeps its own copy, since it would fill the dictionary with
     * one-off strings.
     */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new MessagingPayload(
                dictionary.shared(broker),
                sent,
                temporary(destination) ? destination : dictionary.shared(destination),
                failed,
                linkedTraceId);
    }

    /**
     * Whether {@code destination} is one a broker names for a single conversation: an ActiveMQ or JMS temporary queue
     * or topic ({@code temp-queue://}, {@code temp-topic://}, {@code ID:…}), a RabbitMQ server-named queue
     * ({@code amq.gen-…}), or a bare UUID, as Artemis names its temporary queues.
     */
    static boolean temporary(String destination) {
        if (destination == null) {
            return false;
        }
        return destination.startsWith("temp-queue://")
                || destination.startsWith("temp-topic://")
                || destination.startsWith("ID:")
                || destination.startsWith("amq.gen-")
                || UUID.matcher(destination).matches();
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
