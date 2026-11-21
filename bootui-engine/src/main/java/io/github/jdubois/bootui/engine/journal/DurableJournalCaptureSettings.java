package io.github.jdubois.bootui.engine.journal;

import java.time.Duration;
import java.util.Objects;

/** Bounds and cadence for asynchronous {@link DurableJournalCapture}. */
public record DurableJournalCaptureSettings(
        int maxPendingEntries,
        long maxPendingBytes,
        int flushThresholdEntries,
        long flushThresholdBytes,
        int maxFlushEntries,
        Duration flushInterval,
        Duration heartbeatInterval,
        Duration closeTimeout) {

    public DurableJournalCaptureSettings {
        if (maxPendingEntries <= 0 || maxPendingBytes <= 0) {
            throw new IllegalArgumentException("pending bounds must be positive");
        }
        if (flushThresholdEntries <= 0 || flushThresholdBytes <= 0 || maxFlushEntries <= 0) {
            throw new IllegalArgumentException("flush thresholds and chunk bounds must be positive");
        }
        flushInterval = positive("flushInterval", flushInterval);
        heartbeatInterval = positive("heartbeatInterval", heartbeatInterval);
        closeTimeout = positive("closeTimeout", closeTimeout);
        if (flushThresholdEntries > maxPendingEntries) {
            throw new IllegalArgumentException("flushThresholdEntries must not exceed maxPendingEntries");
        }
        if (flushThresholdBytes > maxPendingBytes) {
            throw new IllegalArgumentException("flushThresholdBytes must not exceed maxPendingBytes");
        }
    }

    public static DurableJournalCaptureSettings defaults() {
        return new DurableJournalCaptureSettings(
                4_096,
                16L * 1024 * 1024,
                256,
                1024L * 1024,
                128,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(3));
    }

    /** Maps durable startup settings to this lifecycle's bounded queue and timer controls. */
    public static DurableJournalCaptureSettings of(DurableJournalSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        return new DurableJournalCaptureSettings(
                settings.maxPendingEntries(),
                settings.maxPendingBytes(),
                Math.min(settings.maxPendingEntries(), 128),
                Math.min(settings.maxPendingBytes(), 1024L * 1024),
                128,
                settings.flushInterval(),
                settings.flushInterval(),
                Duration.ofSeconds(3));
    }

    private static Duration positive(String field, Duration duration) {
        Objects.requireNonNull(duration, field + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }
}
