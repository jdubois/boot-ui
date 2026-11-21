package io.github.jdubois.bootui.engine.journal;

/**
 * A point-in-time view of asynchronous durable capture progress.
 *
 * <p>{@code durableWatermark} is the greatest original runtime-journal sequence whose append was acknowledged by the
 * backend. {@code pending} and {@code pendingBytes} remain volatile until a later acknowledgement; on a crash only the
 * durable watermark is guaranteed to have crossed the configured backend boundary.</p>
 *
 * @param state lifecycle state
 * @param accepted entries received by the listener
 * @param pending accepted, non-quarantined entries not yet durably acknowledged, including the in-flight chunk
 * @param pendingBytes encoded masked bytes represented by {@code pending}
 * @param durableWatermark greatest sequence acknowledged as durable
 * @param dropped entries shed by the bounded queue or final shutdown
 * @param quarantined rejected rows that cannot be retried safely
 * @param lastError latest backend or poison-row error, or {@code null}
 * @param lastFlushAtEpochMillis wall-clock time of the latest successful or attempted flush
 * @param closedAtEpochMillis wall-clock time close completed, or {@code 0} while open
 * @param finalFlushCompleted whether close observed an empty durable tail
 */
public record DurableJournalCaptureStatus(
        String state,
        long accepted,
        int pending,
        long pendingBytes,
        long durableWatermark,
        long dropped,
        long quarantined,
        String lastError,
        long lastFlushAtEpochMillis,
        long closedAtEpochMillis,
        boolean finalFlushCompleted) {

    /** Entries still available only in process memory at the end of capture. */
    public int volatileTail() {
        return pending;
    }
}
