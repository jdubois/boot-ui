package io.github.jdubois.bootui.engine.kafka;

import io.github.jdubois.bootui.core.dto.KafkaMessageDto;
import io.github.jdubois.bootui.core.dto.KafkaReport;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;

/**
 * Framework-neutral mapping from {@link KafkaActivityRecorder} to the dedicated Kafka panel's
 * {@link KafkaReport}/{@link KafkaMessageDto} DTOs, shared verbatim by both adapters (the Spring
 * {@code KafkaController} and the Quarkus {@code KafkaResource}) so every rendered entry is
 * byte-for-byte equivalent for equivalent input — the same guarantee {@link KafkaActivityEntries} gives
 * the Live Activity {@code MESSAGING} entries fed by the same recorder.
 */
public final class KafkaMessageDtos {

    private KafkaMessageDtos() {}

    /**
     * Assembles the full panel report from a live recorder's current state. Only call this once the
     * caller has already established that a Kafka integration is present (Kafka classpath/capability
     * gating is a per-adapter concern, decided before this method is reached); the returned report is
     * always {@code available}.
     */
    public static KafkaReport toReport(KafkaActivityRecorder recorder) {
        var messages = recorder.recent().stream()
                .filter(message -> message.protocol() == KafkaActivityRecorder.Protocol.KAFKA)
                .map(KafkaMessageDtos::toDto)
                .toList();
        return new KafkaReport(
                true,
                null,
                recorder.isKafkaEnabled(),
                recorder.isCaptureKey(),
                recorder.getMaxEntries(),
                recorder.totalKafkaCaptured(),
                messages.size(),
                messages);
    }

    /** Maps a single captured Kafka message to its panel DTO. */
    public static KafkaMessageDto toDto(CapturedMessage message) {
        return new KafkaMessageDto(
                message.id(),
                message.timestamp(),
                message.direction().name(),
                message.topic(),
                message.partition(),
                message.offset(),
                message.key(),
                message.durationMillis(),
                message.success(),
                message.errorMessage(),
                message.groupId(),
                message.listenerId());
    }
}
