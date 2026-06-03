package io.github.jdubois.bootui.core.dto;

/**
 * Current Micrometer cache metrics for one cache, when cache meters are registered.
 */
public record CacheMetricsDto(
        boolean available,
        Double hits,
        Double misses,
        Double hitRatio,
        Double puts,
        Double evictions,
        Double removals,
        Double size) {}
