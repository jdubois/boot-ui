package io.github.jdubois.bootui.core.dto;

/**
 * One backing tier of a cache, as far as the cache implementation exposes it through public, supported APIs.
 *
 * <p>A single-level cache reports exactly one tier; a cache whose implementation does not describe its storage
 * reports none and is marked opaque instead of having a tier invented for it. BootUI never infers tier order or
 * locality from an implementation's class name.</p>
 *
 * @param id a stable identity for the tier within its cache, used for ordering and UI keys
 * @param name a short display name for the tier
 * @param level the parent/child order, {@code 0} being the tier consulted first
 * @param implementationType the fully-qualified class name backing this tier, or {@code null} when unknown
 * @param locality {@code LOCAL}, {@code DISTRIBUTED} or {@code UNKNOWN}; only ever set when the implementation
 *     explicitly exposes it
 * @param maximumSize the configured maximum entry count when the tier exposes one, otherwise {@code null}
 * @param expiryPolicy a human-readable summary of the configured expiry, or {@code null} when not exposed
 * @param policyNote a caveat about how {@code maximumSize} and {@code expiryPolicy} were obtained — for
 *     example that they come from static configuration rather than the live cache, or that the cache is
 *     bounded by weight rather than entry count — or {@code null} when no qualification is needed
 * @param statistics native statistics for this tier; never {@code null}, but possibly unavailable
 */
public record CacheTierDto(
        String id,
        String name,
        int level,
        String implementationType,
        String locality,
        Long maximumSize,
        String expiryPolicy,
        String policyNote,
        CacheStatisticsDto statistics) {}
