package io.github.jdubois.bootui.engine.cache;

/**
 * The kind of cache access {@link CacheActivityRecorder} captured, mirroring the operations the Live
 * Activity panel's {@code CACHE} event surfaces (see {@code docs/PLAN.md} §3.4): a read that found a
 * value is a {@link #HIT}, one that did not is a {@link #MISS}, and {@link #PUT}/{@link #EVICT}/
 * {@link #CLEAR} mirror a cache's write/invalidate operations.
 */
public enum CacheActivityOperation {
    HIT,
    MISS,
    PUT,
    EVICT,
    CLEAR
}
