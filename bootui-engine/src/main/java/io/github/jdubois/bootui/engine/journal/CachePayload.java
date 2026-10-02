package io.github.jdubois.bootui.engine.journal;

/**
 * A cache access's payload: the cache, the operation, such as {@code HIT} or {@code EVICT}, and up to four application
 * frames above it. Never its key.
 */
public record CachePayload(String cacheName, String operation, ApplicationFrames frames)
        implements RuntimeEventPayload {

    /** An access without application frames. */
    public CachePayload(String cacheName, String operation) {
        this(cacheName, operation, null);
    }

    /** This access with its cache name, operation, and frames replaced by the run's shared copies. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new CachePayload(
                dictionary.shared(cacheName),
                dictionary.shared(operation),
                frames == null ? null : frames.interned(dictionary));
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 16
                + JournalDictionary.retained(dictionary, cacheName)
                + JournalDictionary.retained(dictionary, operation)
                + (frames == null ? 0 : frames.estimatedBytes());
    }
}
