package io.github.jdubois.bootui.engine.journal;

/**
 * A cache access's payload: the cache, the operation, such as {@code HIT} or {@code EVICT}, and up to four application
 * frames above it. Never its key.
 *
 * @param codePathStamp the BootUI agent's code-paths stamp of the instrumented method open on the issuing thread when the
 *     access was recorded ({@code docs/PLAN-v2.md} §5.14, M5-4c), which names the request-tree node that issued it, or {@code 0}
 *     when unknown: without the agent, or when the recorder ran on another thread
 */
public record CachePayload(String cacheName, String operation, ApplicationFrames frames, long codePathStamp)
        implements RuntimeEventPayload {

    /** An access without a code-paths stamp. */
    public CachePayload(String cacheName, String operation, ApplicationFrames frames) {
        this(cacheName, operation, frames, 0L);
    }

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
                ApplicationFrames.interned(frames, dictionary),
                codePathStamp);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 24
                + JournalDictionary.retained(dictionary, cacheName)
                + JournalDictionary.retained(dictionary, operation)
                + (frames == null ? 0 : frames.estimatedBytes());
    }
}
