package io.github.jdubois.bootui.core.dto;

/**
 * One cache access correlated to a profiled request.
 *
 * <p>Carries only what the Live Activity {@code CACHE} entry shows: never a raw key or value, even under
 * full value exposure, only the short, non-reversible key hash the cache recorder computed at capture
 * time.</p>
 *
 * @param timestamp epoch milliseconds of the access
 * @param managerName the bean name of the cache manager that owns the cache
 * @param cacheName the name of the cache accessed
 * @param operation the kind of access: {@code HIT}, {@code MISS}, {@code PUT}, {@code EVICT}, or
 *     {@code CLEAR}
 * @param keyHash the short hash of the accessed key, or {@code null} when no single key was involved
 * @param thread the thread that performed the access
 */
public record RequestProfileCacheAccessDto(
        long timestamp, String managerName, String cacheName, String operation, String keyHash, String thread) {}
