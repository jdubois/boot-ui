package io.github.jdubois.bootui.core.dto;

/**
 * One captured AMQP (RabbitMQ) publisher send or {@code @RabbitListener}/{@code @Incoming}
 * consumer delivery.
 *
 * <p>Only metadata is ever captured, never the message body/payload: an AMQP message body is an
 * arbitrary, potentially large and sensitive application payload with no generic masking strategy
 * (unlike a SQL statement or a config value), so it is out of scope entirely. This is the same
 * {@link ActivityEntryDto} data already merged into the Live Activity stream as {@code MESSAGING}
 * entries; this DTO exposes the identical fields as their own dedicated, filterable panel.</p>
 *
 * @param id sequence number, increasing in capture order
 * @param timestamp epoch milliseconds when the message was captured
 * @param direction {@code PUBLISH} or {@code CONSUME}
 * @param exchange the AMQP exchange name (blank for the default exchange)
 * @param routingKey the AMQP routing key
 * @param queue the queue name the consumer received from, or {@code null} for a published message
 * @param durationMillis the delivery duration in milliseconds, or {@code null} when unknown (a
 *     publisher send never carries one — the {@code MessagePostProcessor} hook runs before the
 *     actual {@code basicPublish} and there is no post-send callback without publisher confirms)
 * @param success whether the publish/delivery completed without error
 * @param errorMessage the failure message when {@code success} is {@code false}
 * @param correlationId a short SHA-256 hash of the AMQP correlation ID, or {@code null} when
 *     correlation-ID capture is disabled or the message carried none
 */
public record RabbitMessageDto(
        long id,
        long timestamp,
        String direction,
        String exchange,
        String routingKey,
        String queue,
        Long durationMillis,
        boolean success,
        String errorMessage,
        String correlationId) {}
