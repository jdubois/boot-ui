package io.github.jdubois.bootui.core.dto;

/**
 * How a bounded capture buffer (HTTP Exchanges, SQL Trace, or REST Client) retains its records, so a panel never
 * implies that its window is complete.
 *
 * <p>A BootUI-owned buffer reserves a bounded share of its capacity for failed or slow records. Routine records are
 * evicted first, and the reserved share evicts its own oldest record only once it is full, so recent failures outlive
 * a flood of successful traffic. The reservation comes out of the configured capacity; it never adds memory.</p>
 *
 * <p>When the application supplies its own recorder (on Spring, its own {@code HttpExchangeRepository} or
 * {@code HttpExchangesFilter}), BootUI cannot see or change how records are retained: {@code applicationManaged} is
 * {@code true}, {@code retained} counts the records BootUI read, and every other count is {@code null}.</p>
 *
 * @param applicationManaged whether the application, rather than BootUI, owns the recorder and its retention
 * @param capacity maximum records the buffer retains, or {@code null} when application-managed
 * @param reservedCapacity records of that capacity reserved for failed or slow records, or {@code null} when
 *     application-managed; {@code 0} disables the reservation
 * @param retained records currently retained
 * @param reserved failed or slow records currently held in the reserved share, never more than
 *     {@code reservedCapacity}, or {@code null} when application-managed
 * @param evicted records dropped since startup because the buffer was full, or {@code null} when application-managed
 * @param slowThresholdMillis duration at or above which a record is classified as slow and eligible for the reserved
 *     share, {@code 0} when slow classification is disabled (only failures are reserved), or {@code null} when
 *     application-managed
 */
public record CaptureRetentionDto(
        boolean applicationManaged,
        Integer capacity,
        Integer reservedCapacity,
        int retained,
        Integer reserved,
        Long evicted,
        Long slowThresholdMillis) {

    /** Retention of a recorder the application owns: BootUI only knows how many records it read. */
    public static CaptureRetentionDto applicationManaged(int retained) {
        return new CaptureRetentionDto(true, null, null, retained, null, null, null);
    }
}
