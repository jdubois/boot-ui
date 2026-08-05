package io.github.jdubois.bootui.engine.kafka;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;

/**
 * Framework-neutral mapping of a {@link CapturedMessage} to a flat {@code MESSAGING}
 * {@link ActivityEntryDto} for the Live Activity stream. Both the {@link KafkaActivityRecorder} and
 * {@link ActivityEntryDto} are framework-neutral, so this pure mapping is shared verbatim by both
 * adapters (the Spring {@code LiveActivityService} and the Quarkus {@code LiveActivityResource}) to keep
 * every rendered Kafka entry byte-for-byte equivalent for equivalent input.
 *
 * <p>Unlike {@code SQL}/{@code EXCEPTION}/{@code SECURITY} entries, no request-parent correlation is
 * attempted (BootUI has no trace id on the producer/consumer thread for Kafka today), so every entry is
 * top-level; see {@code docs/PLAN.md} §3.4 for the nesting this can grow into once messaging spans carry
 * a correlation id. Duration is only known for consumed messages (the producer callback carries no
 * send-start timestamp).
 */
public final class KafkaActivityEntries {

    private static final String TYPE_MESSAGING = "MESSAGING";
    private static final String SEVERITY_OK = "OK";
    private static final String SEVERITY_ERROR = "ERROR";

    private KafkaActivityEntries() {}

    /** Maps a single captured Kafka message to its {@code MESSAGING} activity entry. */
    public static ActivityEntryDto toEntry(CapturedMessage message) {
        String severity = message.success() ? SEVERITY_OK : SEVERITY_ERROR;
        String arrow = message.direction() == KafkaActivityRecorder.Direction.PRODUCE ? "→" : "←";
        String summary = arrow + " " + message.topic();
        if (message.partition() != null) {
            summary += " [" + message.partition() + "]";
        }
        StringBuilder detail = new StringBuilder();
        if (message.key() != null) {
            detail.append("key=").append(message.key());
        }
        if (message.offset() != null) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append("offset=").append(message.offset());
        }
        if (!message.success() && message.errorMessage() != null) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append(message.errorMessage());
        }
        // durationMillis is already null for PRODUCE (the producer callback carries no send-start
        // timestamp); passed through as-is here.
        Long durationMs = message.durationMillis();
        return new ActivityEntryDto(
                "kafka-" + message.id(),
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
