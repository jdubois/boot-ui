package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral snapshot of one cache manager known to a {@link CacheProvider}: its name, native
 * implementation type, no-op flag and the caches it currently exposes. The list is returned <em>unsorted</em>
 * and <em>metrics-free</em> — the engine {@code CacheService} applies BootUI's stable ordering and overlays
 * Micrometer metrics on top.
 *
 * <p>Adapters with a single, unnamed cache manager (Quarkus) return a one-element list with a synthetic
 * manager name; adapters with multiple named manager beans (Spring) return one entry per bean.</p>
 *
 * @param name the manager name (a bean name on Spring, a synthetic constant on Quarkus)
 * @param type the fully-qualified class name of the manager implementation
 * @param noOp whether this is a no-op manager (caching effectively disabled)
 * @param caches the caches this manager currently exposes, unsorted and metrics-free
 */
public record CacheManagerSnapshot(String name, String type, boolean noOp, List<CacheSnapshot> caches) {

    public CacheManagerSnapshot {
        caches = caches == null ? List.of() : List.copyOf(caches);
    }
}
