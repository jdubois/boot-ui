package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One cache known to a cache manager.
 *
 * @param managerName the owning cache manager's name
 * @param name the cache name
 * @param nativeType the fully-qualified class name of the underlying native cache, or {@code null}
 * @param size an estimated entry count when the native cache safely exposes one, otherwise {@code null}
 * @param metrics Micrometer {@code cache.*} meters overlaid for this cache; never {@code null}
 * @param opaque whether the implementation exposes no tier structure through public APIs
 * @param opaqueReason why the tier structure is unknown, or {@code null} when tiers were discovered
 * @param tiers the discovered backing tiers, ordered from the tier consulted first; empty when opaque
 * @param statistics whole-cache native statistics; never {@code null}, but possibly unavailable
 */
public record CacheDto(
        String managerName,
        String name,
        String nativeType,
        Long size,
        CacheMetricsDto metrics,
        boolean opaque,
        String opaqueReason,
        List<CacheTierDto> tiers,
        CacheStatisticsDto statistics) {

    public CacheDto {
        tiers = DtoCollections.immutableCopy(tiers);
    }
}
