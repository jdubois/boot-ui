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
 */
public record GcPayload(
        String collector,
        long gcId,
        String action,
        String cause,
        boolean pause,
        long heapBeforeBytes,
        long heapAfterBytes)
        implements RuntimeEventPayload {

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new GcPayload(
                dictionary.shared(collector),
                gcId,
                dictionary.shared(action),
                dictionary.shared(cause),
                pause,
                heapBeforeBytes,
                heapAfterBytes);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 40
                + JournalDictionary.retained(dictionary, collector)
                + JournalDictionary.retained(dictionary, action)
                + JournalDictionary.retained(dictionary, cause);
    }
}
