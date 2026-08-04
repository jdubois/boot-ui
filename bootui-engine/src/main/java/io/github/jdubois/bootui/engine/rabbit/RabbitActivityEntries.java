package io.github.jdubois.bootui.engine.rabbit;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder.CapturedMessage;

/**
 * Framework-neutral mapping of a {@link CapturedMessage} to a flat {@code MESSAGING}
 * {@link ActivityEntryDto} for the Live Activity stream. Both the
 * {@link RabbitActivityRecorder} and {@link ActivityEntryDto} are framework-neutral, so this
 * pure mapping is shared verbatim by both adapters (the Spring {@code LiveActivityService} and
 * the Quarkus {@code LiveActivityResource}) to keep every rendered AMQP entry byte-for-byte
 * equivalent for equivalent input.
 *
 * <p>Unlike {@code SQL}/{@code EXCEPTION}/{@code SECURITY} entries, no request-parent
 * correlation is attempted (BootUI has no trace id on the publisher/consumer thread for
 * RabbitMQ today), so every entry is top-level; see {@code docs/PLAN.md} §3.4 for the nesting
 * this can grow into once messaging spans carry a correlation id. Duration is only known for
 * consumed messages (the publisher hook runs before the actual send with no post-send callback
 * available without publisher confirms).</p>
 */
public final class RabbitActivityEntries {

    private static final String TYPE_MESSAGING = "MESSAGING";
    private static final String SEVERITY_OK = "OK";
    private static final String SEVERITY_ERROR = "ERROR";

    private RabbitActivityEntries() {}

    /** Maps a single captured AMQP message to its {@code MESSAGING} activity entry. */
    public static ActivityEntryDto toEntry(CapturedMessage message) {
        String severity = message.success() ? SEVERITY_OK : SEVERITY_ERROR;
        String arrow = message.direction() == RabbitActivityRecorder.Direction.PUBLISH ? "→" : "←";

        String summary;
        if (message.direction() == RabbitActivityRecorder.Direction.PUBLISH) {
            // Publish: show exchange/routingKey or just routingKey for the default exchange.
            String exchange = message.exchange();
            String routingKey = message.routingKey();
            if (exchange != null && !exchange.isBlank()) {
                summary = arrow + " " + exchange + "/" + (routingKey != null ? routingKey : "");
            } else {
                summary = arrow + " " + (routingKey != null && !routingKey.isBlank() ? routingKey : "(default)");
            }
        } else {
            // Consume: show the queue name (most useful identifier for the consumer).
            String queue = message.queue();
            summary = arrow + " " + (queue != null && !queue.isBlank() ? queue : "(unknown queue)");
        }

        StringBuilder detail = new StringBuilder();
        if (message.direction() == RabbitActivityRecorder.Direction.CONSUME) {
            // Add routing metadata in the detail for consumed messages.
            String exchange = message.exchange();
            if (exchange != null && !exchange.isBlank()) {
                detail.append("exchange=").append(exchange);
            }
            String routingKey = message.routingKey();
            if (routingKey != null && !routingKey.isBlank()) {
                if (detail.length() > 0) {
                    detail.append(' ');
                }
                detail.append("routingKey=").append(routingKey);
            }
        }
        if (message.correlationId() != null) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append("correlationId=").append(message.correlationId());
        }
        if (!message.success() && message.errorMessage() != null) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append(message.errorMessage());
        }
        // durationMillis is already null for PUBLISH (the publisher hook carries no post-send
        // timestamp without publisher confirms); passed through as-is here.
        Long durationMs = message.durationMillis();
        return new ActivityEntryDto(
                "rabbit-" + message.id(),
                TYPE_MESSAGING,
                message.timestamp(),
                severity,
                summary,
                detail.length() > 0 ? detail.toString() : null,
                durationMs,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                false);
    }
}
