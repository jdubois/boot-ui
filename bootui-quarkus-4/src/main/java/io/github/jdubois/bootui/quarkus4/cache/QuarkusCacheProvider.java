package io.github.jdubois.bootui.quarkus4.cache;

import io.github.jdubois.bootui.engine.support.CacheExpiryText;
import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheOperationDiscovery;
import io.github.jdubois.bootui.spi.CacheProvider;
import io.github.jdubois.bootui.spi.CacheSnapshot;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import io.github.jdubois.bootui.spi.CacheTierSnapshot;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CaffeineCache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus {@link CacheProvider}: the cache-specific seam behind the shared engine {@code CacheService},
 * backed by the Quarkus {@link CacheManager}. It is the <strong>sole</strong> importer of the
 * {@code io.quarkus.cache.*} API in BootUI; the engine stays cache-API-free.
 *
 * <p>This class is constructed only by {@link io.github.jdubois.bootui.quarkus4.BootUiCacheProducer}, which the
 * deployment processor excludes from bean discovery unless the {@code CACHE} capability is present (R2), so the
 * {@code io.quarkus.cache} types it references are never linked in a cache-absent application.</p>
 *
 * <p>Quarkus binds caches through build-time annotations ({@code @CacheResult}/{@code @CacheInvalidate}) on
 * methods, with no runtime registry of which methods carry them — so {@link #operations()} is intentionally
 * empty (reduced fidelity relative to Spring's {@code CacheOperationSource} discovery). The panel still shows
 * the live cache names, Caffeine sizes, tier configuration, Micrometer metrics and the clear action.</p>
 *
 * <p>Quarkus's public {@code CaffeineCache} interface exposes no statistics accessor, so every tier reports
 * statistics as unavailable with that reason rather than reaching into {@code CaffeineCacheImpl} by
 * reflection. Configured maximum size and expiry come from the same MicroProfile Config keys the application
 * set, read without touching cache contents.</p>
 */
public class QuarkusCacheProvider implements CacheProvider {

    private static final String MANAGER_NAME = "cacheManager";

    private static final String PROVIDER_CAFFEINE = "Caffeine";

    /**
     * Quarkus's public {@code CaffeineCache} exposes no getter for its current limits, only the
     * {@code setMaximumSize}/{@code setExpireAfter*} setters the Dev UI and application code can call. The
     * limits below therefore come from the application's own configuration, and are labelled as such rather
     * than presented as a live reading the way the Spring adapter's {@code Policy} values are.
     */
    private static final String CONFIGURATION_NOTE = "Read from the application's Quarkus configuration, not"
            + " from the running cache: a later CaffeineCache.setMaximumSize() or setExpireAfter...() call is"
            + " not reflected here.";

    private final CacheManager cacheManager;

    private final Config config;

    public QuarkusCacheProvider(CacheManager cacheManager, Config config) {
        this.cacheManager = cacheManager;
        this.config = config;
    }

    @Override
    public boolean available() {
        return cacheManager != null;
    }

    @Override
    public boolean clearEnabled() {
        return config.getOptionalValue("bootui.cache.clear-enabled", Boolean.class)
                .orElse(Boolean.TRUE);
    }

    @Override
    public List<CacheManagerSnapshot> managers() {
        if (cacheManager == null) {
            return List.of();
        }
        List<CacheSnapshot> caches = new ArrayList<>();
        for (String name : cacheManager.getCacheNames()) {
            Optional<Cache> cache = cacheManager.getCache(name);
            if (cache.isEmpty()) {
                continue;
            }
            caches.add(toSnapshot(name, cache.get()));
        }
        String type = cacheManager.getClass().getName();
        boolean noOp = type.toLowerCase(Locale.ROOT).contains("noop");
        // Quarkus builds its single cache manager at build time from the annotated methods it found, so the
        // set of caches is fixed for the running application and never composed of delegate managers.
        return List.of(new CacheManagerSnapshot(
                MANAGER_NAME,
                type,
                noOp,
                CacheManagerSnapshot.COMPOSITION_SIMPLE,
                CacheManagerSnapshot.DYNAMIC_NO,
                List.of(),
                caches));
    }

    @Override
    public CacheOperationDiscovery operations() {
        return CacheOperationDiscovery.empty();
    }

    @Override
    public Optional<String> clearUnavailableReason() {
        if (cacheManager == null) {
            return Optional.of("No cache manager is available.");
        }
        return Optional.empty();
    }

    @Override
    public boolean evict(String managerName, String cacheName) {
        if (cacheManager == null) {
            throw new IllegalStateException("No cache manager is available.");
        }
        Optional<Cache> cache = cacheManager.getCache(cacheName);
        if (cache.isEmpty()) {
            return false;
        }
        cache.get().invalidateAll().await().indefinitely();
        return true;
    }

    private CacheSnapshot toSnapshot(String name, Cache cache) {
        String nativeType = cache.getClass().getName();
        if (!(cache instanceof CaffeineCache caffeine)) {
            String reason = "BootUI cannot describe the storage of this cache through the public Quarkus cache"
                    + " API, so its tiers and statistics are unknown.";
            return new CacheSnapshot(
                    name, nativeType, null, List.of(), CacheStatisticsSnapshot.unavailable(null, reason), reason);
        }
        // The public CaffeineCache interface exposes only keySet() (which copies the key set), not the
        // O(1) estimatedSize() on the internal CaffeineCacheImpl. We accept the copy rather than couple
        // to the internal impl by reflection: the Quarkus console is prod-dark (wired only in dev/test),
        // where caches are small and this read happens only on an explicit panel GET.
        long size = caffeine.keySet().size();
        CacheStatisticsSnapshot statistics = CacheStatisticsSnapshot.unavailable(
                PROVIDER_CAFFEINE,
                "Quarkus's public CaffeineCache API exposes no statistics, so hits and misses are unknown."
                        + " Enable Micrometer cache metrics to record them instead.");
        // The tier id matches the Spring adapter's Caffeine tier so the same provider reads the same way on
        // every stack; the id is scoped to its own cache, so the cache name would only be redundant here.
        CacheTierSnapshot tier = new CacheTierSnapshot(
                "caffeine",
                "Caffeine",
                0,
                nativeType,
                CacheTierSnapshot.LOCALITY_LOCAL,
                configuredMaximumSize(name),
                configuredExpiry(name),
                CONFIGURATION_NOTE,
                statistics);
        return new CacheSnapshot(name, nativeType, size, List.of(tier), statistics, null);
    }

    /**
     * The configured maximum entry count, read from the application's own Quarkus configuration. A value that
     * is absent or not a number is reported as unknown rather than guessed.
     */
    private Long configuredMaximumSize(String cacheName) {
        String value = firstConfiguredValue(cacheName, "maximum-size");
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** A human-readable summary of the configured expiry, or {@code null} when none is configured. */
    private String configuredExpiry(String cacheName) {
        return CacheExpiryText.summary(
                configuredDuration(cacheName, "expire-after-write", "expire after write"),
                configuredDuration(cacheName, "expire-after-access", "expire after access"));
    }

    /**
     * Phrases a configured duration through the same engine helper the Spring adapter uses, so the same expiry
     * reads identically on both stacks. A value the runtime cannot convert is shown verbatim rather than
     * dropped, because a policy the application genuinely configured should not disappear from the panel.
     */
    private String configuredDuration(String cacheName, String suffix, String label) {
        String raw = firstConfiguredValue(cacheName, suffix);
        if (raw == null) {
            return null;
        }
        Duration duration;
        try {
            duration = firstConfiguredValue(cacheName, suffix, Duration.class);
        } catch (RuntimeException ex) {
            return CacheExpiryText.verbatim(label, raw);
        }
        String phrase = "expire after write".equals(label)
                ? CacheExpiryText.expireAfterWrite(duration)
                : CacheExpiryText.expireAfterAccess(duration);
        return phrase == null ? CacheExpiryText.verbatim(label, raw) : phrase;
    }

    /**
     * Reads a per-cache Caffeine setting, falling back to the Quarkus-wide default for the same setting. The
     * raw string is used verbatim so a duration keeps the form the application configured.
     */
    private String firstConfiguredValue(String cacheName, String suffix) {
        String value = firstConfiguredValue(cacheName, suffix, String.class);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Resolves one Caffeine setting, trying the quoted and unquoted per-cache forms before the Quarkus-wide
     * default. Quarkus accepts {@code quarkus.cache.caffeine."orders".maximum-size} and, for names that need
     * no quoting, {@code quarkus.cache.caffeine.orders.maximum-size}; MicroProfile Config matches the property
     * name literally, so both spellings have to be asked for or a genuinely configured limit reads as unknown.
     */
    private <T> T firstConfiguredValue(String cacheName, String suffix, Class<T> type) {
        return config.getOptionalValue(quotedKey(cacheName, suffix), type)
                .or(() -> config.getOptionalValue(plainKey(cacheName, suffix), type))
                .or(() -> config.getOptionalValue(defaultKey(suffix), type))
                .orElse(null);
    }

    private static String quotedKey(String cacheName, String suffix) {
        return "quarkus.cache.caffeine.\"" + cacheName + "\"." + suffix;
    }

    private static String plainKey(String cacheName, String suffix) {
        return "quarkus.cache.caffeine." + cacheName + "." + suffix;
    }

    private static String defaultKey(String suffix) {
        return "quarkus.cache.caffeine." + suffix;
    }
}
