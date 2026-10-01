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

    @Override
    public int estimatedBytes() {
        return 16
                + RuntimeEvent.stringBytes(broker)
                + RuntimeEvent.stringBytes(destination)
                + RuntimeEvent.stringBytes(linkedTraceId);
    }
}
