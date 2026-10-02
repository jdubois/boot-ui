package io.github.jdubois.bootui.engine.journal;

/**
 * One completed garbage collection, from the JVM's GC notifications ({@code docs/PLAN-v2.md} §5.11). Requests join it
 * by {@code (collector, gcId)}, through the {@link io.github.jdubois.bootui.engine.resources.GcPauseRange}s their
 * segments recorded, never by time. The envelope's duration is the collection's, in whole milliseconds as the JVM
 * reports it.
 *
 * @param collector the collector's name, such as {@code G1 Young Generation}
 * @param gcId the collection's id, which equals the collector's collection count once it completed
 * @param action the JVM's action, such as {@code end of minor GC}
 * @param cause the JVM's cause, such as {@code G1 Evacuation Pause}
 * @param pause whether the collector reports stop-the-world pauses; a concurrent cycle, such as ZGC's, does not
 * @param heapBeforeBytes heap used before the collection, or {@code -1} when unknown
 * @param heapAfterBytes heap used after the collection, or {@code -1} when unknown
 * @param oldGenBeforeBytes old-generation occupancy before the collection, or {@code -1} when the heap has no old
 *     generation BootUI recognizes
 * @param oldGenAfterBytes old-generation occupancy after the collection, or {@code -1} when unknown
 */
public record GcPayload(
        String collector,
        long gcId,
        String action,
        String cause,
        boolean pause,
        long heapBeforeBytes,
        long heapAfterBytes,
        long oldGenBeforeBytes,
        long oldGenAfterBytes)
        implements RuntimeEventPayload {

    /** A collection whose old-generation occupancy is unknown, for a JVM or test that does not report it. */
    public GcPayload(
            String collector,
            long gcId,
            String action,
            String cause,
            boolean pause,
            long heapBeforeBytes,
            long heapAfterBytes) {
        this(collector, gcId, action, cause, pause, heapBeforeBytes, heapAfterBytes, -1, -1);
    }

    /**
     * Whether the collection reclaimed old-generation space, as a full or mixed collection does and a young one, which
     * only promotes into it, does not ({@code heap-growth-after-gc}, §5.11).
     */
    public boolean reclaimedOldGeneration() {
        return oldGenBeforeBytes >= 0 && oldGenAfterBytes >= 0 && oldGenAfterBytes < oldGenBeforeBytes;
    }

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new GcPayload(
                dictionary.shared(collector),
                gcId,
                dictionary.shared(action),
                dictionary.shared(cause),
                pause,
                heapBeforeBytes,
                heapAfterBytes,
                oldGenBeforeBytes,
                oldGenAfterBytes);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 56
                + JournalDictionary.retained(dictionary, collector)
                + JournalDictionary.retained(dictionary, action)
                + JournalDictionary.retained(dictionary, cause);
    }
}
