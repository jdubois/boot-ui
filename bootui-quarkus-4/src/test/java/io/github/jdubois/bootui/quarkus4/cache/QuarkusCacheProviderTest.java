package io.github.jdubois.bootui.quarkus4.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.quarkus4.StubConfig;
import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheSnapshot;
import io.github.jdubois.bootui.spi.CacheTierSnapshot;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CaffeineCache;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Quarkus cache tier and statistics reporting, without booting Quarkus.
 *
 * <p>These cover the parts the {@code @QuarkusTest} integration tests cannot exercise cheaply: the several
 * spellings of a Quarkus cache configuration key, and every way a configured value can be missing, empty or
 * unusable. The rule under test throughout is that BootUI reports what the application actually configured, or
 * reports nothing — never a guess.</p>
 */
class QuarkusCacheProviderTest {

    private static CacheTierSnapshot onlyTier(Map<String, String> config) {
        return onlyCache(config).tiers().get(0);
    }

    private static CacheSnapshot onlyCache(Map<String, String> config) {
        List<CacheManagerSnapshot> managers = new QuarkusCacheProvider(
                        new StubCacheManager(new StubCaffeineCache("orders", Set.of("a", "b"))), new StubConfig(config))
                .managers();
        assertThat(managers).hasSize(1);
        assertThat(managers.get(0).caches()).hasSize(1);
        return managers.get(0).caches().get(0);
    }

    @Test
    void readsTheQuotedPerCacheConfigurationKeys() {
        CacheTierSnapshot tier = onlyTier(Map.of(
                "quarkus.cache.caffeine.\"orders\".maximum-size", "1000",
                "quarkus.cache.caffeine.\"orders\".expire-after-write", "5M"));

        assertThat(tier.id()).isEqualTo("caffeine");
        assertThat(tier.level()).isZero();
        assertThat(tier.locality()).isEqualTo(CacheTierSnapshot.LOCALITY_LOCAL);
        assertThat(tier.maximumSize()).isEqualTo(1000L);
        assertThat(tier.expiryPolicy()).isEqualTo("expire after write 5m");
        assertThat(tier.policyNote()).contains("configuration");
    }

    @Test
    void readsTheUnquotedPerCacheConfigurationKeys() {
        // Quarkus accepts an unquoted segment for a name that needs no quoting, and MicroProfile Config matches
        // property names literally, so a limit configured this way must not read as unknown.
        CacheTierSnapshot tier = onlyTier(Map.of(
                "quarkus.cache.caffeine.orders.maximum-size", "50",
                "quarkus.cache.caffeine.orders.expire-after-access", "PT30S"));

        assertThat(tier.maximumSize()).isEqualTo(50L);
        assertThat(tier.expiryPolicy()).isEqualTo("expire after access 30s");
    }

    @Test
    void fallsBackToTheQuarkusWideDefault() {
        CacheTierSnapshot tier = onlyTier(Map.of("quarkus.cache.caffeine.maximum-size", "7"));

        assertThat(tier.maximumSize()).isEqualTo(7L);
    }

    @Test
    void prefersThePerCacheValueOverTheQuarkusWideDefault() {
        CacheTierSnapshot tier = onlyTier(Map.of(
                "quarkus.cache.caffeine.maximum-size", "7",
                "quarkus.cache.caffeine.\"orders\".maximum-size", "9"));

        assertThat(tier.maximumSize()).isEqualTo(9L);
    }

    @Test
    void reportsAnUnconfiguredCacheAsUnknownRatherThanZero() {
        CacheTierSnapshot tier = onlyTier(Map.of());

        assertThat(tier.maximumSize()).isNull();
        assertThat(tier.expiryPolicy()).isNull();
    }

    @Test
    void reportsANonNumericMaximumSizeAsUnknown() {
        CacheTierSnapshot tier = onlyTier(Map.of("quarkus.cache.caffeine.\"orders\".maximum-size", "lots"));

        assertThat(tier.maximumSize()).isNull();
    }

    @Test
    void keepsAnUnparseableExpiryVisibleVerbatim() {
        // A policy the application genuinely configured should never disappear from the panel just because
        // BootUI could not convert it; it is shown as configured, so the operator can see what is wrong.
        CacheTierSnapshot tier = onlyTier(Map.of("quarkus.cache.caffeine.\"orders\".expire-after-write", "soon"));

        assertThat(tier.expiryPolicy()).isEqualTo("expire after write soon");
    }

    @Test
    void combinesBothConfiguredExpiries() {
        CacheTierSnapshot tier = onlyTier(Map.of(
                "quarkus.cache.caffeine.\"orders\".expire-after-write", "60",
                "quarkus.cache.caffeine.\"orders\".expire-after-access", "10"));

        assertThat(tier.expiryPolicy()).isEqualTo("expire after write 1m, expire after access 10s");
    }

    @Test
    void reportsStatisticsAsUnavailableWithTheReasonAndTheAlternative() {
        CacheSnapshot cache = onlyCache(Map.of());

        assertThat(cache.statistics().available()).isFalse();
        assertThat(cache.statistics().hits()).isNull();
        assertThat(cache.statistics().misses()).isNull();
        assertThat(cache.statistics().unavailableReason())
                .contains("no statistics")
                .contains("Micrometer");
        // The tier repeats the cache-level state so a tier row never looks like it has counters of its own.
        assertThat(cache.tiers().get(0).statistics().available()).isFalse();
    }

    @Test
    void reportsTheLocalEntryCount() {
        assertThat(onlyCache(Map.of()).size()).isEqualTo(2L);
    }

    @Test
    void reportsACacheItCannotDescribeAsOpaqueInsteadOfGuessing() {
        QuarkusCacheProvider provider =
                new QuarkusCacheProvider(new StubCacheManager(new PlainCache("orders")), StubConfig.empty());

        CacheSnapshot cache = provider.managers().get(0).caches().get(0);

        assertThat(cache.tiers()).isEmpty();
        assertThat(cache.opaqueReason()).isNotBlank();
        assertThat(cache.statistics().available()).isFalse();
    }

    @Test
    void reportsNoManagerAtAllWhenCachingIsAbsent() {
        QuarkusCacheProvider provider = new QuarkusCacheProvider(null, StubConfig.empty());

        assertThat(provider.available()).isFalse();
        assertThat(provider.managers()).isEmpty();
        assertThat(provider.clearUnavailableReason()).isPresent();
    }

    @Test
    void clearIsEnabledByDefaultAndCanBeTurnedOff() {
        StubCacheManager manager = new StubCacheManager(new StubCaffeineCache("orders", Set.of()));

        assertThat(new QuarkusCacheProvider(manager, StubConfig.empty()).clearEnabled())
                .isTrue();
        assertThat(new QuarkusCacheProvider(manager, new StubConfig(Map.of("bootui.cache.clear-enabled", "false")))
                        .clearEnabled())
                .isFalse();
    }

    private record StubCacheManager(Cache cache) implements CacheManager {

        @Override
        public Collection<String> getCacheNames() {
            return List.of(cache.getName());
        }

        @Override
        public Optional<Cache> getCache(String name) {
            return cache.getName().equals(name) ? Optional.of(cache) : Optional.empty();
        }
    }

    /** A cache that is not a {@link CaffeineCache}, standing in for a store BootUI cannot describe. */
    private static final class PlainCache extends PlainCacheBase {

        private PlainCache(String name) {
            super(name);
        }
    }

    private static final class StubCaffeineCache extends PlainCacheBase implements CaffeineCache {

        private final Set<Object> keys;

        private StubCaffeineCache(String name, Set<String> keys) {
            super(name);
            this.keys = new LinkedHashSet<>(keys);
        }

        @Override
        public Set<Object> keySet() {
            return keys;
        }

        @Override
        public <V> CompletableFuture<V> getIfPresent(Object key) {
            return null;
        }

        @Override
        public <V> void put(Object key, CompletableFuture<V> value) {}

        @Override
        public <V> void put(Object key, CompletableFuture<V> value, Duration duration) {}

        @Override
        public void setExpireAfterWrite(Duration duration) {}

        @Override
        public void setExpireAfterAccess(Duration duration) {}

        @Override
        public void setMaximumSize(long maximumSize) {}
    }

    /** Shared no-op {@link Cache} plumbing so the Caffeine stub only declares what the test cares about. */
    private abstract static class PlainCacheBase implements Cache {

        private final String name;

        private PlainCacheBase(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getDefaultKey() {
            return "default";
        }

        @Override
        public <K, V> Uni<V> get(K key, Function<K, V> valueLoader) {
            return Uni.createFrom().item(valueLoader.apply(key));
        }

        @Override
        public <K, V> Uni<V> getAsync(K key, Function<K, Uni<V>> valueLoader) {
            return valueLoader.apply(key);
        }

        @Override
        public Uni<Void> invalidate(Object key) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> invalidateAll() {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> invalidateIf(Predicate<Object> predicate) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public <T extends Cache> T as(Class<T> type) {
            return type.cast(this);
        }
    }
}
