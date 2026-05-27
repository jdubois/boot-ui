package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class CacheControllerTests {

    @Test
    void cacheReportIsStableWhenNoCacheManagersArePresent() throws Exception {
        MockMvc mvc = standaloneSetup(controller(new StaticListableBeanFactory(), null, null, new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/cache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheAvailable").value(false))
                .andExpect(jsonPath("$.clearEnabled").value(true))
                .andExpect(jsonPath("$.managerCount").value(0))
                .andExpect(jsonPath("$.cacheCount").value(0))
                .andExpect(jsonPath("$.managers").isEmpty())
                .andExpect(jsonPath("$.operations").isEmpty());
    }

    @Test
    void cacheReportListsManagersCachesMetricsAndAnnotatedOperations() throws Exception {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager("sample-products", "sample-greetings");
        manager.getCache("sample-products").put("library", "BootUI Starter");

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("cacheManager", manager);
        factory.addBean("cachedService", new CachedService());

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("cache.gets", "cache", "sample-products", "name", "cacheManager", "result", "hit")
                .increment(3);
        registry.counter("cache.gets", "cache", "sample-products", "name", "cacheManager", "result", "miss")
                .increment(1);
        registry.counter("cache.puts", "cache", "sample-products", "name", "cacheManager")
                .increment(2);

        MockMvc mvc = standaloneSetup(controller(factory, new AnnotationCacheOperationSource(), registry,
                new BootUiProperties())).build();

        mvc.perform(get("/bootui/api/cache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheAvailable").value(true))
                .andExpect(jsonPath("$.managerCount").value(1))
                .andExpect(jsonPath("$.cacheCount").value(2))
                .andExpect(jsonPath("$.managers[0].name").value("cacheManager"))
                .andExpect(jsonPath("$.managers[0].caches[?(@.name=='sample-products')].size").value(1))
                .andExpect(jsonPath("$.managers[0].caches[?(@.name=='sample-products')].metrics.hits").value(3.0))
                .andExpect(jsonPath("$.managers[0].caches[?(@.name=='sample-products')].metrics.misses").value(1.0))
                .andExpect(jsonPath("$.managers[0].caches[?(@.name=='sample-products')].metrics.hitRatio").value(0.75))
                .andExpect(jsonPath("$.operationCount").value(2))
                .andExpect(jsonPath("$.operations[?(@.operation=='@Cacheable')]").exists())
                .andExpect(jsonPath("$.operations[?(@.operation=='@CacheEvict')]").exists());
    }

    @Test
    void clearCanBeDisabledByConfiguration() throws Exception {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager("sample-products");
        Cache cache = manager.getCache("sample-products");
        cache.put("library", "BootUI Starter");

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("cacheManager", manager);
        MockMvc mvc = standaloneSetup(controller(factory, null, null, clearDisabledProperties())).build();

        mvc.perform(post("/bootui/api/cache/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"managerName":"cacheManager","cacheName":"sample-products","confirm":true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("disabled"));
        assertThat(cache.get("library")).isNotNull();
    }

    @Test
    void clearRequiresConfirmationAndDoesNotMutateCache() throws Exception {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager("sample-products");
        Cache cache = manager.getCache("sample-products");
        cache.put("library", "BootUI Starter");

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("cacheManager", manager);
        MockMvc mvc = standaloneSetup(controller(factory, null, null, clearEnabledProperties())).build();

        mvc.perform(post("/bootui/api/cache/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"managerName":"cacheManager","cacheName":"sample-products"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("confirmation_required"));
        assertThat(cache.get("library")).isNotNull();
    }

    @Test
    void clearSingleCacheClearsKnownCache() throws Exception {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager("sample-products");
        Cache cache = manager.getCache("sample-products");
        cache.put("library", "BootUI Starter");

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("cacheManager", manager);
        MockMvc mvc = standaloneSetup(controller(factory, null, null, clearEnabledProperties())).build();

        mvc.perform(post("/bootui/api/cache/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"managerName":"cacheManager","cacheName":"sample-products","confirm":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cleared"))
                .andExpect(jsonPath("$.clearedCaches").value(1))
                .andExpect(jsonPath("$.caches[0]").value("cacheManager/sample-products"));
        assertThat(cache.get("library")).isNull();
    }

    @Test
    void clearAllClearsEveryKnownCacheAcrossManagers() throws Exception {
        ConcurrentMapCacheManager primary = new ConcurrentMapCacheManager("sample-products");
        ConcurrentMapCacheManager secondary = new ConcurrentMapCacheManager("sample-greetings");
        primary.getCache("sample-products").put("library", "BootUI Starter");
        secondary.getCache("sample-greetings").put("hello", "Hello");

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("primaryCacheManager", primary);
        factory.addBean("secondaryCacheManager", secondary);
        MockMvc mvc = standaloneSetup(controller(factory, null, null, clearEnabledProperties())).build();

        mvc.perform(post("/bootui/api/cache/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"all":true,"confirm":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cleared"))
                .andExpect(jsonPath("$.clearedCaches").value(2));
        assertThat(primary.getCache("sample-products").get("library")).isNull();
        assertThat(secondary.getCache("sample-greetings").get("hello")).isNull();
    }

    @Test
    void clearUnknownCacheReturnsNotFound() throws Exception {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager("sample-products");
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("cacheManager", manager);
        MockMvc mvc = standaloneSetup(controller(factory, null, null, clearEnabledProperties())).build();

        mvc.perform(post("/bootui/api/cache/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"managerName":"cacheManager","cacheName":"missing","confirm":true}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("not_found"));
    }

    @SuppressWarnings("unchecked")
    private static CacheController controller(ListableBeanFactory factory,
                                              CacheOperationSource operationSource,
                                              MeterRegistry meterRegistry,
                                              BootUiProperties properties) {
        ObjectProvider<ListableBeanFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(factory);

        ObjectProvider<CacheOperationSource> operationSourceProvider = mock(ObjectProvider.class);
        when(operationSourceProvider.orderedStream()).thenReturn(
                operationSource == null ? Stream.empty() : Stream.of(operationSource));

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
        when(meterRegistryProvider.getIfUnique()).thenReturn(meterRegistry);
        when(meterRegistryProvider.orderedStream()).thenReturn(
                meterRegistry == null ? Stream.empty() : Stream.of(meterRegistry));

        return new CacheController(factoryProvider, operationSourceProvider, meterRegistryProvider, properties);
    }

    private static BootUiProperties clearEnabledProperties() {
        BootUiProperties properties = new BootUiProperties();
        properties.getCache().setClearEnabled(true);
        return properties;
    }

    private static BootUiProperties clearDisabledProperties() {
        BootUiProperties properties = new BootUiProperties();
        properties.getCache().setClearEnabled(false);
        return properties;
    }

    public static class CachedService {

        @Cacheable(cacheNames = "sample-products", key = "#category", condition = "#category != null")
        public String productName(String category) {
            return category;
        }

        @CacheEvict(cacheNames = "sample-products", allEntries = true, beforeInvocation = true)
        public void resetProducts() {
        }
    }
}
