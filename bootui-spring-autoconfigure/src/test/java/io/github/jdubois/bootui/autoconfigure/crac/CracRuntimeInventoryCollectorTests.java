package io.github.jdubois.bootui.autoconfigure.crac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
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
import org.springframework.jms.connection.SingleConnectionFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Exercises the collector against lightweight real application contexts so bean-type lookup,
 * singleton boundaries, and optional runtime observations match production wiring.
 */
class CracRuntimeInventoryCollectorTests {

    @Test
    void reportsUnavailableInventoryWhenApplicationContextIsNull() {
        CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(null);

        assertThat(inventory.connectionPoolBeans()).isEmpty();
        assertThat(inventory.cacheManagerBeans()).isEmpty();
        assertThat(inventory.hikariPoolIssues()).isEmpty();
        assertThat(inventory.unmanagedTaskBeans()).isEmpty();
        assertThat(inventory.available()).isFalse();
        assertThat(inventory.warnings()).isNotEmpty();
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
    void remainsClassloadingSafeWhenManagedClientDependenciesAreHidden() {
        ClassLoader noClients = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.springframework.data.redis.")
                        || name.startsWith("org.springframework.amqp.")
                        || name.startsWith("org.springframework.kafka.")
                        || name.startsWith("org.springframework.jms.")
                        || name.startsWith("jakarta.jms.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(noClients);
            context.register(EmptyConfig.class);
            context.refresh();

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.available()).isTrue();
            assertThat(inventory.managedConnectionPoolBeans()).isEmpty();
            assertThat(inventory.connectionPoolBeans()).isEmpty();
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
    void leavesSingleHikariLifecyclePairUnverifiedEvenWithSuspensionEnabled() {
        try (AnnotationConfigApplicationContext context = hikariContext(true, true)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.connectionPoolBeans()).isEmpty();
            assertThat(inventory.hikariPoolIssues())
                    .singleElement()
                    .asString()
                    .contains("pairing is unverified", "counts do not establish target identity")
                    .doesNotContain("allowPoolSuspension=false");
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
    void leavesMatchingMultiPoolAndLifecycleCountsUnverified() {
        try (AnnotationConfigApplicationContext context = multiHikariContext(true, true, true, true)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.hikariPoolIssues())
                    .hasSize(2)
                    .allSatisfy(issue -> assertThat(issue).contains("pairing is unverified"));
        }
    }

    @Test
    void reportsSuspensionIndependentlyOfUnverifiedPairing() {
        try (AnnotationConfigApplicationContext context = multiHikariContext(true, true, false, true)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.hikariPoolIssues())
                    .hasSize(2)
                    .anySatisfy(issue -> assertThat(issue).contains("second", "allowPoolSuspension=false"))
                    .allSatisfy(issue -> assertThat(issue).contains("pairing is unverified"));
        }
    }

    @Test
    void reportsUnmatchedCoverageWhenMultiPoolCountsDiffer() {
        try (AnnotationConfigApplicationContext context = multiHikariContext(true, true, true, false)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            // Two pools but only one lifecycle bean cannot be assumed 1:1; keep the conservative
            // "can't verify" stance instead of guessing which pool the single bean covers.
            assertThat(inventory.hikariPoolIssues())
                    .hasSize(2)
                    .allSatisfy(
                            issue -> assertThat(issue)
                                    .contains(
                                            "checkpoint lifecycle pairing is unverified across 2 Hikari pool(s) and 1 lifecycle bean definition(s)"));
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
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            // Change only the observation after refresh; never request a real startup checkpoint.
            SpringProperties.setProperty("spring.context.checkpoint", "onRefresh");
            assertThat(CracRuntimeInventoryCollector.collect(context).checkpointOnRefresh())
                    .isTrue();
        } finally {
            SpringProperties.setProperty("spring.context.checkpoint", null);
        }
    }

    @Test
    void treatsRestoreArgumentAsHintNotProof() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(
                    context, () -> java.util.List.of("-XX:CRaCRestoreFrom=/opt/crac/checkpoint"));

            assertThat(inventory.restoredProcess()).isFalse();
            assertThat(inventory.warnings()).anyMatch(warning -> warning.contains("launch hint"));
        }
    }

    @Test
    void detectsRestoreOnlyFromPublicRuntimeObservation() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            assertThat(CracRuntimeInventoryCollector.collect(context, List::of, () -> 123L)
                            .restoredProcess())
                    .isTrue();
            assertThat(CracRuntimeInventoryCollector.collect(context, List::of, () -> -1L)
                            .restoredProcess())
                    .isFalse();
            assertThat(CracRuntimeInventoryCollector.collect(context, List::of, () -> null)
                            .restoredProcess())
                    .isFalse();
        }
    }

    @Test
    void failedRestoreAndArgumentObservationsRemainVisibleWithoutExceptionText() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(
                    context,
                    () -> {
                        throw new IllegalStateException("password=argument-secret");
                    },
                    () -> {
                        throw new IllegalStateException("password=restore-secret");
                    });

            assertThat(inventory.available()).isTrue();
            assertThat(inventory.restoredProcess()).isFalse();
            assertThat(inventory.warnings()).hasSize(2);
            assertThat(inventory.warnings().toString()).doesNotContain("argument-secret", "restore-secret");
        }
    }

    @Test
    void readsActualRunningStateAndExactSpringProperty() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            assertThat(CracRuntimeInventoryCollector.collect(context).applicationRunning())
                    .isTrue();
            context.stop();
            assertThat(CracRuntimeInventoryCollector.collect(context).applicationRunning())
                    .isFalse();
            for (String value : List.of("onrefresh", "ONREFRESH", " onRefresh", "onRefresh ")) {
                SpringProperties.setProperty("spring.context.checkpoint", value);
                assertThat(CracRuntimeInventoryCollector.collect(context).checkpointOnRefresh())
                        .isFalse();
            }
        } finally {
            SpringProperties.setProperty("spring.context.checkpoint", null);
        }
    }

    @Test
    void failedBeanMetadataDoesNotBecomeSuccessfulEmptyInventory() {
        org.springframework.context.ApplicationContext context =
                mock(org.springframework.context.ApplicationContext.class);
        when(context.getClassLoader()).thenThrow(new IllegalStateException("password=hidden"));

        CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

        assertThat(inventory.available()).isFalse();
        assertThat(inventory.warnings())
                .singleElement()
                .asString()
                .contains("could not be inspected")
                .doesNotContain("hidden");
    }

    @Test
    void duplicateLifecyclesTargetingOnePoolCannotCoverAnotherPool() {
        try (AnnotationConfigApplicationContext context = multiHikariContext(true, true, true, false)) {
            context.getBeanFactory()
                    .registerSingleton(
                            "duplicate",
                            new HikariCheckpointRestoreLifecycle(context.getBean("first", DataSource.class), context));
            assertThat(CracRuntimeInventoryCollector.collect(context).hikariPoolIssues())
                    .hasSize(2)
                    .allSatisfy(issue -> assertThat(issue).contains("pairing is unverified"));
        }
    }

    @Test
    void missingLifecycleDoesNotHideDisabledSuspension() {
        try (AnnotationConfigApplicationContext context = hikariContext(false, false)) {
            assertThat(CracRuntimeInventoryCollector.collect(context).hikariPoolIssues())
                    .singleElement()
                    .asString()
                    .contains("bean is missing", "allowPoolSuspension=false");
        }
    }

    @Test
    void failedPublicSuspensionGetterIsNotAHealthyEmptyInventory() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            HikariDataSource pool = mock(HikariDataSource.class);
            when(pool.isAllowPoolSuspension()).thenThrow(new IllegalStateException("password=hidden"));
            context.getBeanFactory().registerSingleton("brokenPool", pool);

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.available()).isFalse();
            assertThat(inventory.warnings())
                    .singleElement()
                    .asString()
                    .contains("could not be inspected")
                    .doesNotContain("hidden");
        }
    }

    @Test
    void creditsOnlyExistingConcreteManagedFactoriesWithoutInvokingTheirLifecycle() {
        AtomicBoolean stopped = new AtomicBoolean();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            // Register after refresh so even the test does not start clients or acquire connections.
            context.getBeanFactory().registerSingleton("jms", new SingleConnectionFactory());
            context.getBeanFactory().registerSingleton("rabbit", new CachingConnectionFactory());
            context.getBeanFactory().registerSingleton("kafka", new DefaultKafkaProducerFactory<>(Map.of()));
            context.getBeanFactory().registerSingleton("genericRedis", mock(RedisConnectionFactory.class));
            context.getBeanFactory().registerSingleton("customJms", new SingleConnectionFactory() {
                @Override
                public void stop() {
                    stopped.set(true);
                }
            });

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.available()).isTrue();
            assertThat(inventory.managedConnectionPoolBeans())
                    .hasSize(3)
                    .anyMatch(value -> value.startsWith("jms :"))
                    .anyMatch(value -> value.startsWith("rabbit :"))
                    .anyMatch(value -> value.startsWith("kafka :"));
            assertThat(inventory.connectionPoolBeans())
                    .hasSize(2)
                    .anyMatch(value -> value.startsWith("genericRedis :"))
                    .anyMatch(value -> value.startsWith("customJms :"));
            assertThat(stopped).isFalse();
        }
    }

    @Test
    void exactManagedTypeMetadataIncludesLettuceWithoutConstructingAClient() throws ClassNotFoundException {
        // Avoid compile-time resolution of Lettuce's optional driver signatures on newer javac versions.
        Class<?> lettuceFactory = Class.forName(
                "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory",
                false,
                getClass().getClassLoader());
        assertThat(CracRuntimeInventoryCollector.isKnownManagedType(lettuceFactory))
                .isTrue();
        assertThat(CracRuntimeInventoryCollector.isKnownManagedType(CachingConnectionFactory.class))
                .isTrue();
        assertThat(CracRuntimeInventoryCollector.isKnownManagedType(DefaultKafkaProducerFactory.class))
                .isTrue();
        assertThat(CracRuntimeInventoryCollector.isKnownManagedType(SingleConnectionFactory.class))
                .isTrue();
        assertThat(CracRuntimeInventoryCollector.isKnownManagedType(RedisConnectionFactory.class))
                .isFalse();
        assertThat(CracRuntimeInventoryCollector.isKnownManagedType(CustomManagedFactory.class))
                .isFalse();
    }

    static class CustomManagedFactory extends SingleConnectionFactory {}

    @Test
    void lazyManagedFactoryAndFactoryBeanProductsRemainUninitializedAndUnverified() {
        AtomicBoolean created = new AtomicBoolean();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    "lazyJms",
                    SingleConnectionFactory.class,
                    () -> {
                        created.set(true);
                        return new SingleConnectionFactory();
                    },
                    definition -> definition.setLazyInit(true));
            RootBeanDefinition factory = new RootBeanDefinition(UncreatedHikariFactory.class);
            factory.setLazyInit(true);
            factory.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, HikariDataSource.class);
            context.registerBeanDefinition("factoryPool", factory);
            context.refresh();

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.available()).isTrue();
            assertThat(created).isFalse();
            assertThat(context.getBeanFactory().containsSingleton("factoryPool"))
                    .isFalse();
            assertThat(inventory.managedConnectionPoolBeans()).isEmpty();
            assertThat(inventory.connectionPoolBeans()).anyMatch(value -> value.startsWith("lazyJms :"));
            // Spring's non-eager, singleton-only lookup omits an uninitialized FactoryBean product:
            // even its singleton status is unverified. Do not initialize it just to add an observation.
            assertThat(inventory.hikariPoolIssues()).isEmpty();
        }
    }

    static class UncreatedHikariFactory implements FactoryBean<HikariDataSource> {
        UncreatedHikariFactory() {
            throw new AssertionError("Collector must not initialize FactoryBeans");
        }

        @Override
        public HikariDataSource getObject() {
            throw new AssertionError("Collector must not request FactoryBean products");
        }

        @Override
        public Class<?> getObjectType() {
            return HikariDataSource.class;
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

    /**
     * Registers two independent Hikari pools, mirroring a multi-datasource application where
     * Spring Boot's auto-configuration only wires a {@code HikariCheckpointRestoreLifecycle} for
     * the single-candidate case; additional pools each need their own lifecycle bean registered by
     * the application.
     */
    private static AnnotationConfigApplicationContext multiHikariContext(
            boolean firstAllowsSuspension,
            boolean registerFirstLifecycle,
            boolean secondAllowsSuspension,
            boolean registerSecondLifecycle) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("first", HikariDataSource.class, () -> {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setAllowPoolSuspension(firstAllowsSuspension);
            return dataSource;
        });
        context.registerBean("second", HikariDataSource.class, () -> {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setAllowPoolSuspension(secondAllowsSuspension);
            return dataSource;
        });
        context.refresh();
        if (registerFirstLifecycle) {
            context.getBeanFactory()
                    .registerSingleton(
                            "firstLifecycle",
                            new HikariCheckpointRestoreLifecycle(context.getBean("first", DataSource.class), context));
        }
        if (registerSecondLifecycle) {
            context.getBeanFactory()
                    .registerSingleton(
                            "secondLifecycle",
                            new HikariCheckpointRestoreLifecycle(context.getBean("second", DataSource.class), context));
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
