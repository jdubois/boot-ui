package io.github.jdubois.bootui.engine.journal;

import java.util.Map;

/**
 * The journal's state at one instant, for its status block ({@code docs/PLAN-v2.md} §5.2).
 *
 * @param enabled whether the journal records anything
 * @param instanceId the BootUI instance
 * @param runId the application run the journal records
 * @param lastSequence the sequence number of the newest accepted event, or {@code 0}
 * @param retainedEvents the events retained as evidence
 * @param retainedBytes the bytes those events are estimated to retain
 * @param dictionaryEntries the strings the run's dictionary interned
 * @param dictionaryBytes the bytes the dictionary retains, counted against {@code maxBytes}
 * @param maxEvents the count bound
 * @param maxBytes the byte bound
 * @param reserved failed and slow events held in the reserved share
 * @param reservedCapacity the reserved share of {@code maxEvents}
 * @param evictedByCount events evicted because the count bound was reached
 * @param evictedByBytes events evicted because the byte bound was reached
 * @param bindingBound the bound that forced the latest eviction, {@code COUNT} or {@code BYTES}, or {@code null}
 * @param oldestRetainedEpochMillis when the oldest retained event happened, or {@code null} when none is retained
 * @param queueDepth the events waiting for the dispatcher
 * @param queueCapacity the queue's capacity
 * @param accepted the events accepted since startup, per source
 * @param dropped the events dropped since startup because the queue was full, per source
 * @param listenerFailures the batches a listener failed to process
 */
public record JournalStatus(
        boolean enabled,
        String instanceId,
        String runId,
        long lastSequence,
        int retainedEvents,
        long retainedBytes,
        int dictionaryEntries,
        long dictionaryBytes,
        int maxEvents,
        long maxBytes,
        int reserved,
        int reservedCapacity,
        long evictedByCount,
        long evictedByBytes,
        String bindingBound,
        Long oldestRetainedEpochMillis,
        int queueDepth,
        int queueCapacity,
        Map<JournalSource, Long> accepted,
        Map<JournalSource, Long> dropped,
        long listenerFailures) {

    public JournalStatus {
        accepted = accepted == null ? Map.of() : Map.copyOf(accepted);
        dropped = dropped == null ? Map.of() : Map.copyOf(dropped);
    }

    /** Events dropped from every source. */
    public long droppedTotal() {
        return dropped.values().stream().mapToLong(Long::longValue).sum();
    }
}
