package io.github.jdubois.bootui.spi;

import java.util.List;
import java.util.Optional;

/**
 * Framework-neutral seam behind the Cache panel: it reports the host application's cache managers and caches
 * (their native topology only) and performs cache eviction, while the engine {@code CacheService} owns the
 * framework-neutral concerns — overlaying Micrometer metrics, ordering, counting, and orchestrating the
 * clear action (confirmation, routing, not-found handling, result shaping).
 *
 * <p>The split is deliberate. <strong>Metrics</strong> are read from Micrometer, whose meter naming
 * conventions ({@code cache.gets}, {@code cache.puts}, …, tagged {@code cache=<name>}) are identical on both
 * frameworks, and Micrometer is a sanctioned engine dependency — so metric reading lives in the engine and
 * is shared. <strong>Topology</strong> (which managers/caches exist, their native type and estimated size)
 * and the <strong>eviction primitive</strong> are framework-specific and live here. <strong>Declarative
 * cache-annotation discovery</strong> ({@code @Cacheable} et al.) is a Spring runtime concept with no Quarkus
 * runtime equivalent, so it is returned through {@link #operations()} (populated on Spring, empty on
 * Quarkus).</p>
 *
 * <p>The Spring adapter implements this over {@code org.springframework.cache.CacheManager} beans and
 * {@code CacheOperationSource}; the Quarkus adapter over {@code io.quarkus.cache.CacheManager} (with
 * {@link #operations()} empty).</p>
 */
public interface CacheProvider {

    /**
     * Whether a cache backend is present. {@code false} means no cache infrastructure is available (the
     * engine then serves an empty, unavailable report and refuses to clear). Note this is distinct from
     * "caches exist": a backend can be present with zero caches declared.
     */
    boolean available();

    /**
     * Whether cache clearing is permitted, from {@code bootui.cache.clear-enabled} (default {@code true}).
     * The engine rejects clear requests with a {@code disabled} status when this is {@code false}.
     */
    boolean clearEnabled();

    /**
     * The cache managers and their caches as a framework-neutral, <em>unsorted</em> and
     * <em>metrics-free</em> topology. The engine sorts the managers and caches and overlays Micrometer
     * metrics. Returns an empty list when no caches exist (the report's {@code cacheAvailable} is then
     * {@code false}).
     */
    List<CacheManagerSnapshot> managers();

    /**
     * Declarative cache-annotation operations discovered on application beans, already sorted, plus any scan
     * warnings. Populated on Spring; {@link CacheOperationDiscovery#empty()} on Quarkus.
     */
    CacheOperationDiscovery operations();

    /**
     * The framework-specific reason the clear action cannot proceed, or {@link Optional#empty()} when it can.
     * Lets each adapter keep its own wording (for example Spring's "No CacheManager beans are available.")
     * so the engine never has to encode framework jargon. Checked by the engine after the
     * confirmation/enabled gates and before routing the eviction.
     */
    Optional<String> clearUnavailableReason();

    /**
     * Evicts every entry of the named cache in the named manager, returning whether the cache was actually
     * present to clear. The engine only calls this for caches it has already confirmed exist via
     * {@link #managers()}, so in practice this returns {@code true}; it returns {@code false} only when the
     * manager no longer hands back the cache between the snapshot and the eviction (a TOCTOU race), in which
     * case the engine reports a {@code not_found} status with the canonical "was not returned by manager"
     * message — byte-identical to the pre-extraction Spring behavior. Implementations should let genuine
     * infrastructure failures propagate as a {@link RuntimeException}; the engine catches it and reports a
     * {@code failed} status carrying the exception's simple class name.
     *
     * @param managerName the manager owning the cache
     * @param cacheName the cache to evict
     * @return {@code true} if the cache was found and cleared; {@code false} if the manager no longer exposed it
     */
    boolean evict(String managerName, String cacheName);
}
