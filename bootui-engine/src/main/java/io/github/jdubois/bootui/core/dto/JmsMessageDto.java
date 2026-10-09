package io.github.jdubois.bootui.core.dto;

/**
 * One captured Spring JMS producer send or listener delivery.
 *
 * <p>Only bounded metadata is exposed. The message payload, arbitrary headers/properties, raw provider
 * message ID, and exception message are never retained.
 *
 * @param id sequence number, increasing in capture order
 * @param timestamp epoch milliseconds when the message was captured
 * @param direction {@code PRODUCE} or {@code CONSUME}
 * @param destination the sanitized queue or topic name
 * @param messageId a short SHA-256 hash of the provider-assigned message ID, or {@code null}
 * @param durationMillis the send or delivery duration in milliseconds
 * @param success whether the send or delivery completed without error
 * @param failureType the exception class name when {@code success} is {@code false}
 * @param subscriptionName the durable/shared subscription name, or {@code null}
 * @param listenerId the listener endpoint identifier (falling back to its container factory bean name), or {@code null}
 */
public record JmsMessageDto(
        long id,
        long timestamp,
        String direction,
        String destination,
        String messageId,
        Long durationMillis,
        boolean success,
        String failureType,
        String subscriptionName,
        String listenerId) {}
