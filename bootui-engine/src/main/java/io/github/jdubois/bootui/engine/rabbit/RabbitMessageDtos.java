package io.github.jdubois.bootui.engine.rabbit;

import io.github.jdubois.bootui.core.dto.RabbitMessageDto;
import io.github.jdubois.bootui.core.dto.RabbitReport;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder.CapturedMessage;

/**
 * Framework-neutral mapping from {@link RabbitActivityRecorder} to the dedicated RabbitMQ
 * panel's {@link RabbitReport}/{@link RabbitMessageDto} DTOs, shared verbatim by both adapters
 * (the Spring {@code RabbitController} and the Quarkus {@code RabbitResource}) so every
 * rendered entry is byte-for-byte equivalent for equivalent input — the same guarantee
 * {@link RabbitActivityEntries} gives the Live Activity {@code MESSAGING} entries fed by the
 * same recorder.
 */
public final class RabbitMessageDtos {

    private RabbitMessageDtos() {}

    /**
     * Assembles the full panel report from a live recorder's current state. Only call this once
     * the caller has already established that a RabbitMQ integration is present (RabbitMQ
     * classpath/capability gating is a per-adapter concern, decided before this method is
     * reached); the returned report is always {@code available}.
     */
    public static RabbitReport toReport(RabbitActivityRecorder recorder) {
        var messages = recorder.recent().stream().map(RabbitMessageDtos::toDto).toList();
        return new RabbitReport(
                true,
                null,
                recorder.isEnabled(),
                recorder.isCaptureCorrelationId(),
                recorder.getMaxEntries(),
                recorder.totalCaptured(),
                messages.size(),
                messages);
    }

    /** Maps a single captured AMQP message to its panel DTO. */
    public static RabbitMessageDto toDto(CapturedMessage message) {
        return new RabbitMessageDto(
                message.id(),
                message.timestamp(),
                message.direction().name(),
                message.exchange(),
                message.routingKey(),
                message.queue(),
                message.durationMillis(),
                message.success(),
                message.errorMessage(),
                message.correlationId());
    }
}
