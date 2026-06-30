package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.CacheOperationDto;
import java.util.List;

/**
 * Framework-neutral result of discovering declarative cache annotations on application beans, together with
 * any non-fatal warnings raised during the scan.
 *
 * <p>The Spring adapter populates {@code operations} from {@code CacheOperationSource} (the
 * {@code @Cacheable}/{@code @CachePut}/{@code @CacheEvict} methods it finds), already sorted. The Quarkus
 * adapter returns an empty discovery: Quarkus weaves its cache annotations into interceptors at build time
 * and exposes no runtime API to enumerate them, so the panel shows cache names, sizes, metrics and the clear
 * action there instead of an operation inventory.</p>
 *
 * @param operations the discovered operations (sorted by the provider), or an empty list
 * @param warnings non-fatal scan warnings to surface in the report, or an empty list
 */
public record CacheOperationDiscovery(List<CacheOperationDto> operations, List<String> warnings) {

    public CacheOperationDiscovery {
        operations = operations == null ? List.of() : List.copyOf(operations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** An empty discovery with no operations and no warnings. */
    public static CacheOperationDiscovery empty() {
        return new CacheOperationDiscovery(List.of(), List.of());
    }
}
