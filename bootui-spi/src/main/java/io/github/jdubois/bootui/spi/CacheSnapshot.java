package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral snapshot of one cache known to a {@link CacheProvider}, carrying only the adapter's
 * native topology (name, native implementation type and an estimated entry count). Live Micrometer metrics
 * are deliberately <em>not</em> part of this snapshot: the engine {@code CacheService} overlays them on top
 * from the shared {@code MeterRegistry} so the same metric-reading code serves both adapters.
 *
 * @param name the cache name
 * @param nativeType the fully-qualified class name of the underlying native cache, or {@code null} when it
 *     cannot be determined
 * @param size an estimated entry count when the native cache exposes one, otherwise {@code null}
 */
public record CacheSnapshot(String name, String nativeType, Long size) {}
