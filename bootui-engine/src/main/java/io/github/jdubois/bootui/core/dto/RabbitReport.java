package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the RabbitMQ panel: the captured publisher/consumer activity plus enough
 * context for the UI to explain an empty, paused, or unavailable state.
 *
 * <p>This panel is a dedicated, filterable view over the exact same capture buffer that already
 * feeds Live Activity's {@code MESSAGING} entries (the framework-neutral
 * {@code RabbitActivityRecorder}), so the two views are always in sync.</p>
 *
 * @param available whether a RabbitMQ client/messaging integration is present, so activity
 *     <em>can</em> be captured (mirrors {@code KafkaReport}'s equivalent distinction)
 * @param unavailableReason the reason no RabbitMQ integration was found, or {@code null} when
 *     {@code available}
 * @param capturing whether captures are currently enabled ({@code bootui.rabbitmq.enabled}); when
 *     {@code false} the buffer simply stops growing, it is not cleared
 * @param captureCorrelationIdEnabled whether a hash of the AMQP correlation ID is retained
 * @param maxEntries the configured capture buffer bound
 * @param totalCaptured total messages captured since startup (may exceed {@code maxEntries})
 * @param total number of messages currently retained in the buffer
 * @param messages the retained messages, most recent first
 */
public record RabbitReport(
        boolean available,
        String unavailableReason,
        boolean capturing,
        boolean captureCorrelationIdEnabled,
        int maxEntries,
        long totalCaptured,
        int total,
        List<RabbitMessageDto> messages) {

    public RabbitReport {
        messages = DtoCollections.immutableCopy(messages);
    }

    public static RabbitReport unavailable(String reason, int maxEntries) {
        return new RabbitReport(false, reason, false, false, maxEntries, 0, 0, List.of());
    }
}
