package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One cache manager bean and its currently known caches.
 *
 * @param name the manager name (a bean name on Spring, a synthetic constant on Quarkus)
 * @param type the fully-qualified class name of the manager implementation
 * @param noOp whether this is a no-op manager (caching effectively disabled)
 * @param composition {@code SIMPLE} when the manager owns its caches directly, {@code COMPOSITE} when it
 *     delegates to other managers, {@code DELEGATING} when it wraps exactly one manager, or {@code UNKNOWN}
 * @param dynamicCaches {@code YES}, {@code NO} or {@code UNKNOWN} — whether the manager creates caches on
 *     demand. BootUI never probes for an unknown cache name to find out, because that would create a cache.
 * @param delegateTypes the fully-qualified class names of wrapped managers, when the implementation exposes
 *     them through a public API; empty otherwise
 * @param caches the caches this manager currently exposes
 */
public record CacheManagerDto(
        String name,
        String type,
        boolean noOp,
        String composition,
        String dynamicCaches,
        List<String> delegateTypes,
        List<CacheDto> caches) {

    public CacheManagerDto {
        delegateTypes = DtoCollections.immutableCopy(delegateTypes);
        caches = DtoCollections.immutableCopy(caches);
    }
}
