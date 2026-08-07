package io.github.jdubois.bootui.autoconfigure.crac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.jdbc.HikariCheckpointRestoreLifecycle;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.SpringProperties;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Exercises the collector against lightweight real application contexts so bean-type lookup,
 * singleton boundaries, and optional runtime observations match production wiring.
 */
class CracRuntimeInventoryCollectorTests {

    @Test
    void returnsEmptyInventoryWhenApplicationContextIsNull() {
        CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(null);

        assertThat(inventory.connectionPoolBeans()).isEmpty();
        assertThat(inventory.cacheManagerBeans()).isEmpty();
        assertThat(inventory.hikariPoolIssues()).isEmpty();
        assertThat(inventory.unmanagedTaskBeans()).isEmpty();
    }

    @Test
    void remainsClassloadingSafeWhenHikariIsAbsentFromTheApplicationClassLoader() {
        ClassLoader noHikari = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.zaxxer.hikari.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(noHikari);
            context.register(EmptyConfig.class);
            context.refresh();

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.connectionPoolBeans()).isEmpty();
            assertThat(inventory.hikariPoolIssues()).isEmpty();
        }
    }

    @Test
    void collectsNonHikariPoolBeans() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(PoolConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.connectionPoolBeans())
                    .singleElement()
                    .asString()
                    .contains("dataSource")
                    .contains("DataSource");
            assertThat(inventory.hikariPoolIssues()).isEmpty();
        }
    }

    @Test
    void acceptsHikariPoolWithLifecycleAndSuspensionEnabled() {
        try (AnnotationConfigApplicationContext context = hikariContext(true, true)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.connectionPoolBeans()).isEmpty();
            assertThat(inventory.hikariPoolIssues()).isEmpty();
        }
    }

    @Test
    void reportsHikariPoolWithoutCheckpointLifecycle() {
        try (AnnotationConfigApplicationContext context = hikariContext(true, false)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.hikariPoolIssues())
                    .singleElement()
                    .asString()
                    .contains("HikariCheckpointRestoreLifecycle bean is missing");
        }
    }

    @Test
    void reportsHikariPoolWithSuspensionDisabled() {
        try (AnnotationConfigApplicationContext context = hikariContext(false, true)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.hikariPoolIssues()).singleElement().asString().contains("allowPoolSuspension=false");
        }
    }

    @Test
    void recognizesAnExistingHikariPoolBehindADataSourceProxy() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            HikariDataSource target = new HikariDataSource();
            ProxyFactory proxyFactory = new ProxyFactory();
            proxyFactory.setInterfaces(DataSource.class);
            proxyFactory.setTarget(target);
            context.getBeanFactory().registerSingleton("dataSource", proxyFactory.getProxy());

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.connectionPoolBeans()).isEmpty();
            assertThat(inventory.hikariPoolIssues())
                    .singleElement()
                    .asString()
                    .contains("dataSource : com.zaxxer.hikari.HikariDataSource")
                    .contains("HikariCheckpointRestoreLifecycle bean is missing");
        }
    }

    @Test
    void doesNotResolveADynamicDataSourceProxyTarget() {
        AtomicBoolean targetRequested = new AtomicBoolean();
        TargetSource targetSource = new TargetSource() {
            @Override
            public Class<?> getTargetClass() {
                return HikariDataSource.class;
            }

            @Override
            public boolean isStatic() {
                return false;
            }

            @Override
            public Object getTarget() {
                targetRequested.set(true);
                return mock(HikariDataSource.class);
            }

            @Override
            public void releaseTarget(Object target) {}
        };
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(DataSource.class);
        proxyFactory.setTargetSource(targetSource);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            context.getBeanFactory().registerSingleton("dataSource", proxyFactory.getProxy());

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(targetRequested).isFalse();
            assertThat(inventory.connectionPoolBeans())
                    .singleElement()
                    .asString()
                    .contains("dataSource");
            assertThat(inventory.hikariPoolIssues()).isEmpty();
        }
    }

    @Test
    void doesNotInitializeLazyHikariPoolToInspectSuspension() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(LazyHikariConfig.class)) {
            context.getBeanFactory()
                    .registerSingleton(
                            "hikariCheckpointRestoreLifecycle", mock(HikariCheckpointRestoreLifecycle.class));

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(context.getBeanFactory().containsSingleton("dataSource")).isFalse();
            assertThat(inventory.hikariPoolIssues())
                    .singleElement()
                    .asString()
                    .contains("could not be verified without initializing");
        }
    }

    @Test
    void includesKnownLocalInHeapCacheManager() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(LocalCacheConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.cacheManagerBeans())
                    .singleElement()
                    .asString()
                    .contains("localCacheManager")
                    .contains("ConcurrentMapCacheManager");
        }
    }

    @Test
    void excludesNoOpUnknownAndRemoteCacheManagers() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                NoOpCacheConfig.class, UnknownCacheConfig.class, RedisCacheConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.cacheManagerBeans()).isEmpty();
        }
    }

    @Test
    void collectsOnlyTaskBeansWithoutFullLifecycleSupport() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(TaskExecutorConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.unmanagedTaskBeans())
                    .hasSize(2)
                    .anyMatch(entry -> entry.contains("simpleExecutor") && entry.contains("SimpleAsyncTaskExecutor"))
                    .anyMatch(entry -> entry.contains("simpleScheduler") && entry.contains("SimpleAsyncTaskScheduler"))
                    .noneMatch(entry -> entry.contains("managedExecutor"));
        }
    }

    @Test
    void reportsCracApiPresentReflectingClasspath() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.cracApiPresent()).isFalse();
        }
    }

    @Test
    void reportsCheckpointOnRefreshFromTheSpringFrameworkProperty() {
        SpringProperties.setProperty("spring.context.checkpoint", "onRefresh");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            assertThat(CracRuntimeInventoryCollector.collect(context).checkpointOnRefresh())
                    .isTrue();
        } finally {
            SpringProperties.setProperty("spring.context.checkpoint", null);
        }
    }

    @Test
    void detectsRestoredProcessFromJvmArguments() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(
                    context, () -> java.util.List.of("-XX:CRaCRestoreFrom=/opt/crac/checkpoint"));

            assertThat(inventory.restoredProcess()).isTrue();
        }
    }

    private static AnnotationConfigApplicationContext hikariContext(
            boolean allowPoolSuspension, boolean registerLifecycle) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", HikariDataSource.class, () -> {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setAllowPoolSuspension(allowPoolSuspension);
            return dataSource;
        });
        context.refresh();
        if (registerLifecycle) {
            context.getBeanFactory()
                    .registerSingleton(
                            "hikariCheckpointRestoreLifecycle",
                            new HikariCheckpointRestoreLifecycle(context.getBean(DataSource.class), context));
        }
        return context;
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
        CacheManager noOpCacheManager() {
            return new NoOpCacheManager();
        }
    }

    @Configuration
    static class UnknownCacheConfig {

        @Bean
        CacheManager unknownCacheManager() {
            return mock(CacheManager.class);
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

    @Configuration
    static class TaskExecutorConfig {

        @Bean
        SimpleAsyncTaskExecutor simpleExecutor() {
            return new SimpleAsyncTaskExecutor();
        }

        @Bean
        SimpleAsyncTaskScheduler simpleScheduler() {
            return new SimpleAsyncTaskScheduler();
        }

        @Bean
        ThreadPoolTaskExecutor managedExecutor() {
            return new ThreadPoolTaskExecutor();
        }
    }

    @Configuration
    static class LazyHikariConfig {

        @Bean
        @Lazy
        HikariDataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setAllowPoolSuspension(true);
            return dataSource;
        }
    }
}
