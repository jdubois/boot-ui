package io.github.jdubois.bootui.autoconfigure.cache;

import java.util.List;
import java.util.Optional;
import org.springframework.cache.CacheManager;

/**
 * Describes one Spring {@link CacheManager}'s wrapping/composition structure and dynamic-cache state using
 * only that implementation's public, supported API.
 *
 * <p>Implementations must never determine the dynamic-cache state by asking a manager for a cache name it does
 * not know: a dynamic manager would create that cache as a side effect of BootUI rendering a panel. Anything
 * an implementation cannot establish is reported as {@code UNKNOWN} rather than guessed.</p>
 */
interface SpringCacheManagerInspector {

    /**
     * @param manager the cache manager, already unwrapped from BootUI's activity-capture decorator
     * @return the discovered structure, or empty when this inspector does not recognise the manager
     */
    Optional<Structure> inspect(CacheManager manager);

    /**
     * One inspector's findings for a cache manager.
     *
     * @param composition one of the {@code COMPOSITION_*} constants on {@code CacheManagerSnapshot}
     * @param dynamicCaches one of the {@code DYNAMIC_*} constants on {@code CacheManagerSnapshot}
     * @param delegateTypes wrapped manager class names when publicly exposed, otherwise empty
     */
    record Structure(String composition, String dynamicCaches, List<String> delegateTypes) {}
}
