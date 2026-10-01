package io.github.jdubois.bootui.engine.journal;

/**
 * A message's payload: the broker ({@code kafka}, {@code rabbitmq}, or {@code jms}), whether it was sent or consumed,
 * its destination (topic, exchange, or queue), and whether it failed. Never its key, headers, or body.
 */
public record MessagingPayload(String broker, boolean sent, String destination, boolean failed)
        implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16 + RuntimeEvent.stringBytes(broker) + RuntimeEvent.stringBytes(destination);
    }
}
