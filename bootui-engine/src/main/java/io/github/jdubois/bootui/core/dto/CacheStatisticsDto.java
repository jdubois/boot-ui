package io.github.jdubois.bootui.core.dto;

/**
 * One labelled set of cache effectiveness counters.
 *
 * <p>Every counter set carries its own provenance so an application-lifetime provider counter is never
 * confused with BootUI's own bounded capture:</p>
 *
 * <ul>
 *   <li>{@code source} — {@code NATIVE} (read from the cache provider's own public statistics API),
 *       {@code MICROMETER} (read from registered {@code cache.*} meters) or {@code NONE} when nothing is
 *       available.</li>
 *   <li>{@code provider} — a human-readable name of the API the counters came from (for example
 *       {@code Caffeine}), or {@code null} when unknown.</li>
 *   <li>{@code scope} — {@code CACHE} for whole-cache counters or {@code TIER} for one backing tier.</li>
 *   <li>{@code window} — {@code APPLICATION_LIFETIME} when the counters accumulate for the life of the cache,
 *       or {@code UNKNOWN}. BootUI never resets provider counters, so a provider that resets them itself only
 *       makes the series restart.</li>
 *   <li>{@code since} — an ISO-8601 instant the counters have been accumulating from, when the provider
 *       exposes one, so a counter that was reset externally is still explainable.</li>
 * </ul>
 *
 * <p>Every counter is nullable: {@code null} means "this provider does not expose it", never zero. Ratios are
 * derived by the engine only when the hit and miss counters share the same source, scope and window; when a
 * ratio cannot be derived, {@code hitRatio}/{@code missRatio} are {@code null} and
 * {@code ratioUnavailableReason} explains why (for example, no requests recorded yet).</p>
 */
public record CacheStatisticsDto(
        boolean available,
        String source,
        String provider,
        String scope,
        String window,
        String since,
        String unavailableReason,
        Double requests,
        Double hits,
        Double misses,
        Double hitRatio,
        Double missRatio,
        Double puts,
        Double evictions,
        Double removals,
        Double loadSuccesses,
        Double loadFailures,
        Double size,
        String ratioUnavailableReason) {}
