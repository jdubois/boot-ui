package io.github.jdubois.bootui.engine.journal;

/** A cache access's payload: the cache and the operation, such as {@code HIT} or {@code EVICT}. Never its key. */
public record CachePayload(String cacheName, String operation) implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16 + RuntimeEvent.stringBytes(cacheName) + RuntimeEvent.stringBytes(operation);
    }
}
