package io.github.jdubois.bootui.autoconfigure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hostilecache.HostileCacheManager;
import com.example.sealedcache.SealedCacheManager;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheSnapshot;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import io.github.jdubois.bootui.spi.CacheTierSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Verifies the tier and native-statistics description {@code SpringCacheProvider} builds through
 * {@link SpringCacheInspectors}, against real cache managers rather than mocks of BootUI's own seams.
 *
 * <p>The point of every case is truthfulness: a tier is only reported when the provider's public API
 * describes the storage, statistics are only reported as available when the provider says it is recording,
 * and anything BootUI cannot describe is reported opaque with a reason instead of being guessed at.</p>
 */
class SpringCacheInspectorsTests {

    @SuppressWarnings("unchecked")
    private static SpringCacheProvider provider(CacheManager manager) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("cacheManager", manager);
        ObjectProvider<ListableBeanFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(factory);
        ObjectProvider<CacheOperationSource> operationSourceProvider = mock(ObjectProvider.class);
        when(operationSourceProvider.orderedStream())
                .thenReturn(java.util.stream.Stream.of(new AnnotationCacheOperationSource()));
        return new SpringCacheProvider(
                factoryProvider, operationSourceProvider, new BootUiProperties(), BootUiSelfDataFilter.defaults());
    }

    private static CacheSnapshot onlyCache(CacheManager manager) {
        List<CacheManagerSnapshot> managers = provider(manager).managers();
        assertThat(managers).hasSize(1);
        assertThat(managers.get(0).caches()).hasSize(1);
        return managers.get(0).caches().get(0);
    }

    @Test
    void describesAConcurrentMapCacheAsOneLocalTierWithoutInventingStatistics() {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager("orders");
        manager.getCache("orders").put("k", "v");

        CacheSnapshot cache = onlyCache(manager);

        assertThat(cache.size()).isEqualTo(1L);
        assertThat(cache.opaqueReason()).isNull();
        assertThat(cache.tiers()).hasSize(1);
        CacheTierSnapshot tier = cache.tiers().get(0);
        assertThat(tier.level()).isZero();
        assertThat(tier.locality()).isEqualTo(CacheTierSnapshot.LOCALITY_LOCAL);
        assertThat(tier.implementationType()).startsWith("java.util.concurrent.");
        // A plain map records nothing, and must say so rather than report a series of zeroes.
        assertThat(tier.statistics().available()).isFalse();
        assertThat(tier.statistics().unavailableReason()).isNotBlank();
        assertThat(cache.statistics().available()).isFalse();
    }

    @Test
    void describesTheConcurrentMapCacheManagerStructure() {
        CacheManagerSnapshot manager =
                provider(new ConcurrentMapCacheManager("orders")).managers().get(0);

        assertThat(manager.composition()).isEqualTo(CacheManagerSnapshot.COMPOSITION_SIMPLE);
        assertThat(manager.dynamicCaches()).isEqualTo(CacheManagerSnapshot.DYNAMIC_UNKNOWN);
        assertThat(manager.delegateTypes()).isEmpty();
    }

    @Test
    void readsCaffeineCountersConfiguredMaximumAndExpiry() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats());
        Cache cache = manager.getCache("orders");
        cache.put("hit-me", "value");
        cache.get("hit-me");
        cache.get("absent");

        CacheSnapshot snapshot = onlyCache(manager);

        assertThat(snapshot.tiers()).hasSize(1);
        CacheTierSnapshot tier = snapshot.tiers().get(0);
        assertThat(tier.name()).isEqualTo("Caffeine");
        assertThat(tier.locality()).isEqualTo(CacheTierSnapshot.LOCALITY_LOCAL);
        assertThat(tier.maximumSize()).isEqualTo(500L);
        assertThat(tier.expiryPolicy()).isEqualTo("expire after write 5m");

        CacheStatisticsSnapshot statistics = tier.statistics();
        assertThat(statistics.available()).isTrue();
        assertThat(statistics.provider()).isEqualTo("Caffeine");
        assertThat(statistics.window()).isEqualTo(CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME);
        assertThat(statistics.hits()).isEqualTo(1.0d);
        assertThat(statistics.misses()).isEqualTo(1.0d);
        assertThat(statistics.countersComparable()).isTrue();
        // Caffeine exposes no put or explicit-removal counter, which must read as unknown, not zero.
        assertThat(statistics.puts()).isNull();
        assertThat(statistics.removals()).isNull();
        assertThat(snapshot.statistics().available()).isTrue();
    }

    @Test
    void saysSoWhenCaffeineWasNotBuiltWithRecordStats() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder());
        manager.getCache("orders").put("k", "v");

        CacheSnapshot snapshot = onlyCache(manager);

        assertThat(snapshot.tiers()).hasSize(1);
        CacheStatisticsSnapshot statistics = snapshot.tiers().get(0).statistics();
        assertThat(statistics.available()).isFalse();
        assertThat(statistics.provider()).isEqualTo("Caffeine");
        assertThat(statistics.unavailableReason()).contains("recordStats");
        assertThat(statistics.hits()).isNull();
        // The tier itself is still described: the configuration is known even when the counters are not.
        assertThat(snapshot.tiers().get(0).locality()).isEqualTo(CacheTierSnapshot.LOCALITY_LOCAL);
    }

    @Test
    void doesNotReportAMaximumSizeForAWeightedCaffeineCacheButSaysWhy() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder().maximumWeight(1000).weigher((key, value) -> 1));
        manager.getCache("orders").put("k", "v");

        CacheTierSnapshot tier = onlyCache(manager).tiers().get(0);

        // A weighted maximum is a total weight, not an entry count, so reporting it as a maximum size would
        // be a category error. Staying silent about it would be worse: an unreported maximum reads as an
        // unbounded cache, so the tier has to say the bound exists and is measured differently.
        assertThat(tier.maximumSize()).isNull();
        assertThat(tier.policyNote()).contains("weight");
    }

    @Test
    void keepsAnImmediateCaffeineExpiryVisible() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ZERO));
        manager.getCache("orders");

        CacheTierSnapshot tier = onlyCache(manager).tiers().get(0);

        // Zero means "expire immediately" to Caffeine. Dropping it would render the cache as having no expiry.
        assertThat(tier.expiryPolicy()).isEqualTo("expire after write 0ms");
    }

    @Test
    void classifiesTheCaffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("orders");

        CacheManagerSnapshot snapshot = provider(manager).managers().get(0);

        assertThat(snapshot.composition()).isEqualTo(CacheManagerSnapshot.COMPOSITION_SIMPLE);
        assertThat(snapshot.dynamicCaches()).isEqualTo(CacheManagerSnapshot.DYNAMIC_UNKNOWN);
    }

    @Test
    void describesARedisCacheAsADistributedTierWithoutCountingKeys() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisCacheManager manager = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(10)))
                .initialCacheNames(Set.of("orders"))
                .build();
        manager.afterPropertiesSet();

        CacheSnapshot snapshot = onlyCache(manager);

        assertThat(snapshot.tiers()).hasSize(1);
        CacheTierSnapshot tier = snapshot.tiers().get(0);
        assertThat(tier.locality()).isEqualTo(CacheTierSnapshot.LOCALITY_DISTRIBUTED);
        assertThat(tier.expiryPolicy()).isEqualTo("time to live 10m");
        // Counting Redis keys would be an unsolicited network round trip on panel render.
        assertThat(tier.maximumSize()).isNull();
        assertThat(snapshot.size()).isNull();
        // Statistics collection is off by default, and the no-op collector's all-zero counters must not be
        // mistaken for a genuine series.
        assertThat(tier.statistics().available()).isFalse();
        assertThat(tier.statistics().unavailableReason()).contains("enableStatistics");
    }

    @Test
    void readsRedisCountersOnceStatisticsCollectionIsEnabled() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisCacheManager manager = RedisCacheManager.builder(connectionFactory)
                .enableStatistics()
                .initialCacheNames(Set.of("orders"))
                .build();
        manager.afterPropertiesSet();

        CacheStatisticsSnapshot statistics = onlyCache(manager).tiers().get(0).statistics();

        assertThat(statistics.available()).isTrue();
        assertThat(statistics.provider()).isEqualTo("Spring Data Redis");
        assertThat(statistics.countersComparable()).isTrue();
        // Redis expires entries server-side without telling the client, so there is no eviction counter.
        assertThat(statistics.evictions()).isNull();
        // The collector creates its per-cache entry on first read and stamps it with "now", so a cache that
        // has served no traffic would otherwise report a start instant meaning "you just opened the panel".
        assertThat(statistics.hits()).isZero();
        assertThat(statistics.misses()).isZero();
        assertThat(statistics.since()).isNull();
    }

    @Test
    void reportsTheRedisCacheManagerDynamicCacheState() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisCacheManager fixed = RedisCacheManager.builder(connectionFactory)
                .disableCreateOnMissingCache()
                .initialCacheNames(Set.of("orders"))
                .build();
        fixed.afterPropertiesSet();

        assertThat(provider(fixed).managers().get(0).dynamicCaches()).isEqualTo(CacheManagerSnapshot.DYNAMIC_NO);

        RedisCacheManager dynamic = RedisCacheManager.builder(connectionFactory)
                .initialCacheNames(Set.of("orders"))
                .build();
        dynamic.afterPropertiesSet();

        assertThat(provider(dynamic).managers().get(0).dynamicCaches()).isEqualTo(CacheManagerSnapshot.DYNAMIC_YES);
    }

    @Test
    void describesANoOpCacheHonestly() {
        NoOpCacheManager manager = new NoOpCacheManager();
        manager.getCache("orders");

        CacheSnapshot snapshot = onlyCache(manager);

        assertThat(snapshot.tiers()).hasSize(1);
        assertThat(snapshot.tiers().get(0).name()).isEqualTo("No-op");
        assertThat(snapshot.tiers().get(0).maximumSize()).isZero();
        assertThat(snapshot.size()).isZero();
        assertThat(snapshot.tiers().get(0).statistics().available()).isFalse();
        assertThat(provider(manager).managers().get(0).dynamicCaches()).isEqualTo(CacheManagerSnapshot.DYNAMIC_YES);
    }

    @Test
    void reportsACacheNoInspectorRecognisesAsOpaqueWithAReason() {
        CacheSnapshot snapshot = onlyCache(new SealedCacheManager("orders"));

        assertThat(snapshot.tiers()).isEmpty();
        assertThat(snapshot.opaqueReason()).isNotBlank();
        assertThat(snapshot.statistics().available()).isFalse();
        assertThat(snapshot.statistics().unavailableReason()).isNotBlank();
        assertThat(snapshot.size()).isNull();
    }

    @Test
    void buildsOnlyTheCoreChainWhenTheOptionalProvidersAreAbsent() {
        // This is the whole reason SpringCacheInspectors exists: with Caffeine and Spring Data Redis hidden,
        // their inspectors must never be instantiated, so their types are never linked. Without this test the
        // suite would still pass if the classpath gate were deleted, because the test classpath has both.
        ClassLoader hidden = new HidingClassLoader(
                getClass().getClassLoader(),
                Set.of(
                        "com.github.benmanes.caffeine.cache.Cache",
                        "org.springframework.cache.caffeine.CaffeineCacheManager",
                        "org.springframework.data.redis.cache.RedisCache"));

        SpringCacheInspectors inspectors = SpringCacheInspectors.create(hidden);

        assertThat(inspectors.cacheInspectors()).hasSize(1);
        assertThat(inspectors.cacheInspectors().get(0)).isInstanceOf(CoreSpringCacheInspector.class);
        assertThat(inspectors.managerInspectors()).hasSize(1);
        assertThat(inspectors.managerInspectors().get(0)).isInstanceOf(CoreSpringCacheInspector.class);
    }

    @Test
    void buildsEveryChainWhenTheOptionalProvidersArePresent() {
        SpringCacheInspectors inspectors =
                SpringCacheInspectors.create(getClass().getClassLoader());

        assertThat(inspectors.cacheInspectors()).hasAtLeastOneElementOfType(CaffeineSpringCacheInspector.class);
        assertThat(inspectors.cacheInspectors()).hasAtLeastOneElementOfType(RedisSpringCacheInspector.class);
        assertThat(inspectors.managerInspectors())
                .hasAtLeastOneElementOfType(CaffeineSpringCacheManagerInspector.class);
        // Most specific first: the core fallback must be consulted last or a RedisCacheManager, which is also
        // an AbstractCacheManager, would be classified by the wrong inspector.
        assertThat(inspectors.cacheInspectors().get(inspectors.cacheInspectors().size() - 1))
                .isInstanceOf(CoreSpringCacheInspector.class);
        assertThat(inspectors
                        .managerInspectors()
                        .get(inspectors.managerInspectors().size() - 1))
                .isInstanceOf(CoreSpringCacheInspector.class);
    }

    @Test
    void reportsACacheWhoseNativeStoreThrowsAsOpaqueInsteadOfFailingThePanel() {
        CacheSnapshot snapshot = onlyCache(new HostileCacheManager("orders", new IllegalStateException("boom")));

        assertThat(snapshot.tiers()).isEmpty();
        assertThat(snapshot.opaqueReason()).isNotBlank();
        assertThat(snapshot.statistics().available()).isFalse();
    }

    @Test
    void survivesACacheWhoseNativeStoreRaisesALinkageError() {
        // A half-present optional provider raises NoClassDefFoundError, not an exception. One panel row's
        // worth of detail is an acceptable loss; a 500 on the whole Cache panel is not.
        CacheSnapshot snapshot =
                onlyCache(new HostileCacheManager("orders", new NoClassDefFoundError("com/example/Missing")));

        assertThat(snapshot.tiers()).isEmpty();
        assertThat(snapshot.opaqueReason()).isNotBlank();
        assertThat(snapshot.statistics().available()).isFalse();
    }

    @Test
    void inspectsThroughTheActivityCaptureDecorator() {
        CaffeineCacheManager real = new CaffeineCacheManager();
        real.setCaffeine(Caffeine.newBuilder().recordStats());
        real.getCache("orders").put("k", "v");
        CacheManager wrapped = new CacheActivityCacheManager(real, new CacheActivityRecorder(true, 10), "cacheManager");

        CacheSnapshot snapshot = onlyCache(wrapped);

        // Before the Cache-level unwrap, the recording decorator hid the Caffeine cache and every cache read
        // as opaque as soon as activity capture was enabled.
        assertThat(snapshot.tiers()).hasSize(1);
        assertThat(snapshot.tiers().get(0).name()).isEqualTo("Caffeine");
        assertThat(snapshot.tiers().get(0).statistics().available()).isTrue();
    }

    /** A class loader that pretends the named classes are absent, standing in for an application without them. */
    private static final class HidingClassLoader extends ClassLoader {

        private final Set<String> hidden;

        private HidingClassLoader(ClassLoader parent, Set<String> hidden) {
            super(parent);
            this.hidden = hidden;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hidden.contains(name)) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
