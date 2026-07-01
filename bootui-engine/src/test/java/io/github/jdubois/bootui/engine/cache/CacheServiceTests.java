package io.github.jdubois.bootui.engine.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CacheClearRequest;
import io.github.jdubois.bootui.core.dto.CacheDto;
import io.github.jdubois.bootui.core.dto.CacheManagerDto;
import io.github.jdubois.bootui.core.dto.CacheReport;
import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheOperationDiscovery;
import io.github.jdubois.bootui.spi.CacheProvider;
import io.github.jdubois.bootui.spi.CacheSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the framework-neutral {@link CacheService}. They pin the behavior that must stay
 * byte-identical to the pre-extraction Spring {@code SpringCacheService}: metric overlay + manager-tag
 * fallback, ordering, the clear status/HTTP-code matrix, cleared-entry naming, and — critically — the
 * cache-disappears-between-snapshot-and-eviction edge (clearOne → 404 "was not returned"; clearAll → skip,
 * never abort) plus the genuine-failure → 500 path.
 */
class CacheServiceTests {

    private static final Supplier<MeterRegistry> NO_REGISTRY = () -> null;

    private CacheService service(CacheProvider provider) {
        return new CacheService(provider, NO_REGISTRY, meter -> true);
    }

    private CacheService service(CacheProvider provider, MeterRegistry registry) {
        return new CacheService(provider, () -> registry, meter -> true);
    }

    @Test
    void nullProviderRendersUnavailableReportAndRefusesClear() {
        CacheService service = service(null);

        CacheReport report = service.report();
        assertThat(report.cacheAvailable()).isFalse();
        assertThat(report.managerCount()).isZero();
        assertThat(report.managers()).isEmpty();

        var response = service.clear(new CacheClearRequest(null, null, true, true));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().status()).isEqualTo("unavailable");
    }

    @Test
    void unavailableProviderStillSurfacesOperationsAndWarnings() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.available = false;
        provider.operations = new CacheOperationDiscovery(List.of(), List.of("a warning"));

        CacheReport report = service(provider).report();
        assertThat(report.cacheAvailable()).isFalse();
        assertThat(report.warnings()).containsExactly("a warning");
    }

    @Test
    void reportSortsManagersAndCachesAndCounts() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(
                new CacheManagerSnapshot(
                        "zManager",
                        "ZType",
                        false,
                        List.of(new CacheSnapshot("beta", "Native", 2L), new CacheSnapshot("alpha", "Native", 1L))),
                new CacheManagerSnapshot("aManager", "AType", false, List.of(new CacheSnapshot("solo", "Native", 0L))));

        CacheReport report = service(provider).report();
        assertThat(report.cacheAvailable()).isTrue();
        assertThat(report.managerCount()).isEqualTo(2);
        assertThat(report.cacheCount()).isEqualTo(3);
        assertThat(report.managers().stream().map(CacheManagerDto::name)).containsExactly("aManager", "zManager");
        assertThat(report.managers().get(1).caches().stream().map(CacheDto::name))
                .containsExactly("alpha", "beta");
    }

    @Test
    void reportOverlaysMicrometerMetricsWithManagerTagFallback() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager", "Type", false, List.of(new CacheSnapshot("orders", "Native", 5L))));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // No manager tag at all → engine falls back to the "*" wildcard key, which still resolves the cache.
        registry.counter("cache.gets", Tags.of("cache", "orders", "result", "hit"))
                .increment(8);
        registry.counter("cache.gets", Tags.of("cache", "orders", "result", "miss"))
                .increment(2);

        CacheReport report = service(provider, registry).report();
        CacheDto cache = report.managers().get(0).caches().get(0);
        assertThat(cache.metrics().available()).isTrue();
        assertThat(cache.metrics().hits()).isEqualTo(8.0);
        assertThat(cache.metrics().misses()).isEqualTo(2.0);
        assertThat(cache.metrics().hitRatio()).isEqualTo(0.8);
    }

    @Test
    void clearDisabledIsRejectedWith409() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.clearEnabled = false;

        var response = service(provider).clear(new CacheClearRequest(null, null, true, true));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().status()).isEqualTo("disabled");
    }

    @Test
    void clearWithoutConfirmationIsRejectedWith400() {
        var response = service(new FakeCacheProvider()).clear(new CacheClearRequest(null, null, true, false));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("confirmation_required");
    }

    @Test
    void clearReportsUnavailableReasonWith409() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.clearUnavailableReason = Optional.of("No CacheManager beans are available.");

        var response = provider.clear(true, true);
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().status()).isEqualTo("unavailable");
        assertThat(response.body().message()).isEqualTo("No CacheManager beans are available.");
    }

    @Test
    void clearOneRequiresManagerAndCacheName() {
        var response = service(new FakeCacheProvider()).clear(new CacheClearRequest(null, null, false, true));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("invalid_request");
    }

    @Test
    void clearOneUnknownManagerIs404() {
        var response = service(new FakeCacheProvider()).clear(new CacheClearRequest("missing", "orders", false, true));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().message()).contains("Cache manager 'missing' was not found.");
    }

    @Test
    void clearOneUnknownCacheIs404() {
        FakeCacheProvider provider = singleCache("cacheManager", "orders");

        var response = service(provider).clear(new CacheClearRequest("cacheManager", "ghost", false, true));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().message()).contains("was not found in manager 'cacheManager'");
    }

    @Test
    void clearOneEvictsAndReportsClearedEntry() {
        FakeCacheProvider provider = singleCache("cacheManager", "orders");

        var response = service(provider).clear(new CacheClearRequest("cacheManager", "orders", false, true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("cleared");
        assertThat(response.body().caches()).containsExactly("cacheManager/orders");
        assertThat(provider.evicted).containsExactly("cacheManager/orders");
    }

    @Test
    void clearAllEvictsEveryCacheWithPluralizedMessage() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager",
                "Type",
                false,
                List.of(new CacheSnapshot("orders", "Native", 1L), new CacheSnapshot("customers", "Native", 1L))));

        var response = service(provider).clear(new CacheClearRequest(null, null, true, true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().clearedCaches()).isEqualTo(2);
        assertThat(response.body().message()).isEqualTo("Cleared 2 caches.");
        // Sorted: customers before orders.
        assertThat(response.body().caches()).containsExactly("cacheManager/customers", "cacheManager/orders");
    }

    @Test
    void clearAllSingularMessageForOneCache() {
        var response = singleCache("cacheManager", "orders").clear(true, true);
        assertThat(response.body().message()).isEqualTo("Cleared 1 cache.");
    }

    @Test
    void clearOneOnCacheThatVanishedReturns404NotFound() {
        // The cache is known at snapshot time but the provider reports it absent at eviction (TOCTOU race).
        FakeCacheProvider provider = singleCache("cacheManager", "orders");
        provider.evictReturnsFalse = true;

        var response = service(provider).clear(new CacheClearRequest("cacheManager", "orders", false, true));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().status()).isEqualTo("not_found");
        assertThat(response.body().message()).contains("was not returned by manager 'cacheManager'");
    }

    @Test
    void clearAllSkipsAVanishedCacheInsteadOfAborting() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager",
                "Type",
                false,
                List.of(new CacheSnapshot("orders", "Native", 1L), new CacheSnapshot("vanished", "Native", 1L))));
        provider.evictFalseFor = "vanished";

        var response = service(provider).clear(new CacheClearRequest(null, null, true, true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("cleared");
        // The vanished cache is skipped; the rest are still cleared (no abort).
        assertThat(response.body().caches()).containsExactly("cacheManager/orders");
    }

    @Test
    void clearMapsGenuineEvictionFailureTo500() {
        FakeCacheProvider provider = singleCache("cacheManager", "orders");
        provider.evictThrows = true;

        var response = service(provider).clear(new CacheClearRequest("cacheManager", "orders", false, true));
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.body().message()).contains("(IllegalStateException)");
    }

    private FakeCacheProvider singleCache(String manager, String cache) {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(
                new CacheManagerSnapshot(manager, "Type", false, List.of(new CacheSnapshot(cache, "Native", 1L))));
        return provider;
    }

    /** Package-private fake so the engine test needs no Spring/Quarkus backend. */
    private static final class FakeCacheProvider implements CacheProvider {

        private boolean available = true;
        private boolean clearEnabled = true;
        private List<CacheManagerSnapshot> managers = List.of();
        private CacheOperationDiscovery operations = CacheOperationDiscovery.empty();
        private Optional<String> clearUnavailableReason = Optional.empty();
        private boolean evictReturnsFalse;
        private boolean evictThrows;
        private String evictFalseFor;
        private final List<String> evicted = new ArrayList<>();

        CacheService asService() {
            return new CacheService(this, NO_REGISTRY, meter -> true);
        }

        CacheClearResponse clear(boolean all, boolean confirm) {
            return asService().clear(new CacheClearRequest(null, null, all, confirm));
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean clearEnabled() {
            return clearEnabled;
        }

        @Override
        public List<CacheManagerSnapshot> managers() {
            return managers;
        }

        @Override
        public CacheOperationDiscovery operations() {
            return operations;
        }

        @Override
        public Optional<String> clearUnavailableReason() {
            return clearUnavailableReason;
        }

        @Override
        public boolean evict(String managerName, String cacheName) {
            if (evictThrows) {
                throw new IllegalStateException("boom");
            }
            if (evictReturnsFalse || cacheName.equals(evictFalseFor)) {
                return false;
            }
            evicted.add(managerName + "/" + cacheName);
            return true;
        }
    }
}
