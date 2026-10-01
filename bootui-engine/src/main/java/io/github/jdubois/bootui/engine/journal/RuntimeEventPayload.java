package io.github.jdubois.bootui.engine.journal;

/**
 * The immutable, source-specific part of a {@link RuntimeEvent}, such as a statement's fingerprint or a response's
 * status. Payloads hold no request bodies, bind values, message payloads, or secrets ({@code docs/PLAN-v2.md} §5.2).
 */
public interface RuntimeEventPayload {

    /**
     * An estimate of the bytes this payload retains beyond the envelope, computed once when the event is retained, so
     * the journal can keep its byte bound in O(1). Interned strings count once, in the dictionary, not here.
     */
    int estimatedBytes();

    /**
     * This payload with its repeated strings, such as application frames, replaced by the run's shared copies, called
     * by the dispatcher before it retains the event. The default shares nothing.
     */
    default RuntimeEventPayload interned(JournalDictionary dictionary) {
        return this;
    }
}
