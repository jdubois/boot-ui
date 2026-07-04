package io.github.jdubois.bootui.autoconfigure.crac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Verifies {@link CracRuntimeInventoryCollector} against a real (lightweight) {@link
 * org.springframework.context.ApplicationContext} rather than mocking the bean factory, so the tests
 * exercise the same {@code getBeanNamesForType}/{@code getType} resolution the production collector
 * relies on.
 */
class CracRuntimeInventoryCollectorTests {

    @Test
    void returnsEmptyInventoryWhenApplicationContextIsNull() {
        CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(null);

        assertThat(inventory.connectionPoolBeans()).isEmpty();
        assertThat(inventory.cacheManagerBeans()).isEmpty();
    }

    @Test
    void collectsPoolBeans() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(PoolConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.connectionPoolBeans()).hasSize(1);
            assertThat(inventory.connectionPoolBeans().get(0))
                    .contains("dataSource")
                    .contains("DataSource");
        }
    }

    @Test
    void excludesNoOpCacheManagerFromCacheBeans() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(NoOpCacheConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.cacheManagerBeans()).isEmpty();
        }
    }

    @Test
    void includesLocalInHeapCacheManagerInCacheBeans() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(LocalCacheConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.cacheManagerBeans()).hasSize(1);
            assertThat(inventory.cacheManagerBeans().get(0)).contains("localCacheManager");
        }
    }

    @Test
    void excludesRedisCacheManagerFromCacheBeansBecauseItIsRemoteBacked() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(RedisCacheConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.cacheManagerBeans()).isEmpty();
        }
    }

    @Test
    void localAndRedisCacheManagersCoexistAndOnlyTheLocalOneIsReported() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(LocalCacheConfig.class, RedisCacheConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.cacheManagerBeans()).hasSize(1);
            assertThat(inventory.cacheManagerBeans().get(0)).contains("localCacheManager");
        }
    }

    @Test
    void reportsCracApiPresentReflectingClasspath() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            // org.crac:crac is a test-scope dependency of bootui-engine only (needed there so CRaC
            // readiness fixtures can implement org.crac.Resource); bootui-spring-autoconfigure never
            // declares it, so from this module's classloader it is genuinely absent, exactly like an
            // application that has not added the dependency yet (see CRAC-LIFECYCLE-002).
            assertThat(inventory.cracApiPresent()).isFalse();
        }
    }

    @Configuration
    static class EmptyConfig {}

    @Configuration
    static class PoolConfig {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    @Configuration
    static class NoOpCacheConfig {
        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }

    @Configuration
    static class LocalCacheConfig {
        @Bean
        CacheManager localCacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Configuration
    static class RedisCacheConfig {
        @Bean
        CacheManager redisCacheManager() {
            RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
            return RedisCacheManager.builder(connectionFactory).build();
        }
    }
}
