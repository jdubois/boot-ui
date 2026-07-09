package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the Kafka panel: the captured producer/consumer activity plus enough context for
 * the UI to explain an empty, paused, or unavailable state.
 *
 * <p>This panel is a dedicated, filterable view over the exact same capture buffer that already feeds
 * Live Activity's {@code MESSAGING} entries (the framework-neutral {@code KafkaActivityRecorder}), so the
 * two views are always in sync.
 *
 * @param available whether a Kafka client/messaging integration is present, so activity <em>can</em> be
 *     captured (mirrors {@code SqlTraceReport}'s distinction between "capable of tracing" and "currently
 *     capturing")
 * @param unavailableReason the reason no Kafka integration was found, or {@code null} when {@code available}
 * @param capturing whether captures are currently enabled ({@code bootui.kafka.enabled}); when
 *     {@code false} the buffer simply stops growing, it is not cleared
 * @param captureKeyEnabled whether a hash of the record key is retained alongside each captured entry
 * @param maxEntries the configured capture buffer bound
 * @param totalCaptured total messages captured since startup (may exceed {@code maxEntries})
 * @param total number of messages currently retained in the buffer (before any client-side filtering)
 * @param messages the retained messages, most recent first
 */
public record KafkaReport(
        boolean available,
        String unavailableReason,
        boolean capturing,
        boolean captureKeyEnabled,
        int maxEntries,
        long totalCaptured,
        int total,
        List<KafkaMessageDto> messages) {

    public KafkaReport {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static KafkaReport unavailable(String reason, int maxEntries) {
        return new KafkaReport(false, reason, false, false, maxEntries, 0, 0, List.of());
    }
}
