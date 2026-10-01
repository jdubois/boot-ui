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

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return frames == null ? this : new CachePayload(cacheName, operation, frames.interned(dictionary));
    }

    @Override
    public int estimatedBytes() {
        return 16
                + RuntimeEvent.stringBytes(cacheName)
                + RuntimeEvent.stringBytes(operation)
                + (frames == null ? 0 : frames.estimatedBytes());
    }
}
