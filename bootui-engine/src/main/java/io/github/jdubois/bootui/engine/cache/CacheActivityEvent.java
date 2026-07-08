package io.github.jdubois.bootui.engine.cache;

/**
 * One captured cache access, already reduced to what the Live Activity {@code CACHE} event needs: never
 * the raw key or value (see {@code docs/PLAN.md} §3.4 — "cache keys are hashed rather than shown raw even
 * under full exposure"), only a short, stable {@code keyHash}.
 *
 * @param seq monotonic sequence number assigned at capture time, used as this event's stable id
 * @param timestampMillis epoch millis when the access happened
 * @param managerName the bean name of the cache manager that owns the cache
 * @param cacheName the name of the cache accessed
 * @param operation the kind of access
 * @param keyHash short, stable hash of the accessed key, or {@code null} when no single key is involved
 *     (e.g. {@link CacheActivityOperation#CLEAR})
 * @param traceId distributed trace id active when the access happened, or {@code null} when none
 * @param thread name of the thread that performed the access
 */
public record CacheActivityEvent(
        long seq,
        long timestampMillis,
        String managerName,
        String cacheName,
        CacheActivityOperation operation,
        String keyHash,
        String traceId,
        String thread) {}
