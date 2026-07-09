package io.github.jdubois.bootui.core.dto;

/**
 * One captured Kafka producer send or {@code @KafkaListener}/{@code @Incoming} consumer delivery.
 *
 * <p>Only metadata is ever captured, never the message value/payload: a Kafka record's value is an
 * arbitrary, potentially large and sensitive application payload with no generic masking strategy
 * (unlike a SQL statement or a config value), so it is out of scope entirely. This is the same
 * {@link io.github.jdubois.bootui.core.dto.ActivityEntryDto} data already merged into the Live Activity
 * stream as {@code MESSAGING} entries; this DTO exposes the identical fields as their own dedicated,
 * filterable panel.</p>
 *
 * @param id sequence number, increasing in capture order
 * @param timestamp epoch milliseconds when the message was captured
 * @param direction {@code PRODUCE} or {@code CONSUME}
 * @param topic the Kafka topic name
 * @param partition the partition number, or {@code null} when unknown
 * @param offset the record offset, or {@code null} for a produced message (only known once consumed)
 * @param key a short SHA-256 hash of the record key, or {@code null} when key capture is disabled or the
 *     record carried no key
 * @param durationMillis the send/delivery duration in milliseconds, or {@code null} when unknown (a
 *     producer send never carries one — the underlying callback carries no send-start timestamp)
 * @param success whether the send/delivery completed without error
 * @param errorMessage the failure message when {@code success} is {@code false}
 * @param groupId the consumer group id, or {@code null} for a produced message
 * @param listenerId the listener/channel identifier, or {@code null} for a produced message
 */
public record KafkaMessageDto(
        long id,
        long timestamp,
        String direction,
        String topic,
        Integer partition,
        Long offset,
        String key,
        Long durationMillis,
        boolean success,
        String errorMessage,
        String groupId,
        String listenerId) {}
