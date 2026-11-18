package io.github.jdubois.bootui.autoconfigure.crac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Opcodes;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.jdbc.HikariCheckpointRestoreLifecycle;
import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.SpringProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jms.connection.SingleConnectionFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
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
    void remainsClassloadingSafeWhenHikariAndSpringJdbcAreAbsentFromTheApplicationClassLoader() {
        ClassLoader noHikari = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.zaxxer.hikari.") || name.startsWith("org.springframework.jdbc.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(noHikari);
            context.register(EmptyConfig.class);
            context.refresh();
            context.getBeanFactory().registerSingleton("unknownDataSource", mock(DataSource.class));

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.connectionPoolBeans()).isEmpty();
            assertThat(inventory.hikariPoolIssues()).isEmpty();
            assertThat(inventory.available()).isTrue();
            assertThat(inventory.dataSourceObservations())
                    .singleElement()
                    .asString()
                    .contains("unknownDataSource", "pooling is unverified");
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
                        || name.startsWith("jakarta.jms.")
                        || name.startsWith("jakarta.persistence.")
                        || name.startsWith("org.hibernate.")
                        || name.startsWith("liquibase.")
                        || name.startsWith("org.springframework.boot.flyway.")) {
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
            assertThat(inventory.startupDatabaseAccess()).isEmpty();
        }
    }

    @Test
    void collectsNonHikariPoolBeans() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            JdbcConnectionPool pool = spy(JdbcConnectionPool.create("jdbc:h2:mem:crac", "sa", ""));
            context.getBeanFactory().registerSingleton("dataSource", pool);

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.connectionPoolBeans()).containsExactly("dataSource : org.h2.jdbcx.JdbcConnectionPool");
            assertThat(inventory.hikariPoolIssues()).isEmpty();
            assertThat(inventory.dataSourceObservations()).isEmpty();
            verifyNoInteractions(pool);
        }
    }

    @Test
    void consumerFactoriesAreNotConnectionPools() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            DefaultKafkaConsumerFactory<Object, Object> factory = spy(new DefaultKafkaConsumerFactory<>(Map.of()));
            context.getBeanFactory().registerSingleton("consumers", factory);

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.available()).isTrue();
            assertThat(inventory.connectionPoolBeans()).isEmpty();
            assertThat(inventory.managedConnectionPoolBeans()).isEmpty();
            verifyNoInteractions(factory);
        }
    }

    @Test
    void recognizesTheExactJmsCachingFactoryWithoutInvokingClientOrLifecycleMethods() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            jakarta.jms.ConnectionFactory target = mock(jakarta.jms.ConnectionFactory.class);
            org.springframework.jms.connection.CachingConnectionFactory factory =
                    spy(new org.springframework.jms.connection.CachingConnectionFactory(target));
            context.getBeanFactory().registerSingleton("cachedJms", factory);

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.available()).isTrue();
            assertThat(inventory.managedConnectionPoolBeans())
                    .containsExactly("cachedJms : org.springframework.jms.connection.CachingConnectionFactory");
            assertThat(inventory.connectionPoolBeans()).isEmpty();
            verifyNoInteractions(factory, target);
        }
    }

    @Test
    void knownFactoryNamesWithoutTheSpringLifecycleContractRemainUnverified() throws ClassNotFoundException {
        for (String name : List.of(
                "org.springframework.data.redis.connection.jedis.JedisConnectionFactory",
                "org.springframework.jms.connection.CachingConnectionFactory")) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
            writer.visitEnd();
            Class<?> nonLifecycleType = new ClassLoader(null) {
                @Override
                protected Class<?> findClass(String requested) throws ClassNotFoundException {
                    if (!name.equals(requested)) {
                        throw new ClassNotFoundException(requested);
                    }
                    byte[] bytes = writer.toByteArray();
                    return defineClass(requested, bytes, 0, bytes.length);
                }
            }.loadClass(name);

            assertThat(CracRuntimeInventoryCollector.isKnownManagedType(nonLifecycleType))
                    .as(name + " without Lifecycle")
                    .isFalse();
        }
    }

    @Test
    void distinguishesEmbeddedDelegatingAndUnknownDataSourcesFromPoolEvidence() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EmptyConfig.class)) {
            EmbeddedDatabase embedded = mock(EmbeddedDatabase.class);
            DataSource unknown = mock(DataSource.class);
            DelegatingDataSource delegating = spy(new DelegatingDataSource(unknown));
            LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy();
            lazy.setTargetDataSource(unknown);
            context.getBeanFactory().registerSingleton("embedded", embedded);
            context.getBeanFactory().registerSingleton("delegating", delegating);
            context.getBeanFactory().registerSingleton("lazy", lazy);
            context.getBeanFactory().registerSingleton("unknown", unknown);
            context.getBeanFactory().registerSingleton("unpooled", new DriverManagerDataSource());
            context.getBeanFactory().registerSingleton("pool", new HikariDataSource());
            org.mockito.Mockito.clearInvocations(delegating, unknown);

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.available()).isTrue();
            assertThat(inventory.connectionPoolBeans()).isEmpty();
            assertThat(inventory.managedConnectionPoolBeans()).isEmpty();
            assertThat(inventory.hikariPoolIssues()).singleElement().asString().startsWith("pool :");
            assertThat(inventory.dataSourceObservations())
                    .hasSize(5)
                    .anyMatch(value -> value.startsWith("embedded :") && value.contains("embedded DataSource"))
                    .anyMatch(value -> value.startsWith("delegating :") && value.contains("delegating DataSource"))
                    .anyMatch(value -> value.startsWith("lazy :") && value.contains("delegating DataSource"))
                    .anyMatch(value -> value.startsWith("unpooled :") && value.contains("non-pooling DataSource"))
                    .anyMatch(value -> value.startsWith("unknown :") && value.contains("pooling is unverified"))
                    .allMatch(value -> value.contains("checkpoint handling is unverified"));
            verifyNoInteractions(embedded, unknown, delegating);
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
    void reportsStartupDatabaseAccessWithoutExposingTheJdbcUrl() {
        try (AnnotationConfigApplicationContext context =
                startupDatabaseContext("jdbc:postgresql://db.example:5432/app?password=do-not-expose", Map.of())) {
            context.getBeanFactory().registerSingleton("liquibase", new SpringLiquibase());

            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.startupDatabaseAccess())
                    .hasSize(2)
                    .anyMatch(text -> text.startsWith("Liquibase liquibase"))
                    .anyMatch(text -> text.startsWith("Hibernate boot JDBC metadata access entityManagerFactory"))
                    .allMatch(text -> text.endsWith("without an in-memory JDBC URL: dataSource"))
                    .noneMatch(text -> text.contains("postgresql") || text.contains("do-not-expose"));
        }
    }

    @Test
    void disallowedHibernateMetadataStillReportsSchemaManagement() {
        try (AnnotationConfigApplicationContext context = startupDatabaseContext(
                "jdbc:postgresql://db.example/app",
                Map.of(
                        "hibernate.boot.allow_jdbc_metadata_access",
                        "DISALLOW",
                        "hibernate.hbm2ddl.auto",
                        "validate"))) {
            CracRuntimeInventory inventory = CracRuntimeInventoryCollector.collect(context);

            assertThat(inventory.startupDatabaseAccess())
                    .singleElement()
                    .asString()
                    .startsWith("Hibernate schema management hibernate.hbm2ddl.auto=validate");
        }
        try (AnnotationConfigApplicationContext context = startupDatabaseContext(
                "jdbc:postgresql://db.example/app",
                Map.of("hibernate.boot.allow_jdbc_metadata_access", "false", "hibernate.hbm2ddl.auto", "none"))) {
            assertThat(CracRuntimeInventoryCollector.collect(context).startupDatabaseAccess())
                    .isEmpty();
        }
    }

    @Test
    void inMemoryDatabasesDoNotReportStartupDatabaseAccess() {
        try (AnnotationConfigApplicationContext context = startupDatabaseContext("JDBC:H2:MEM:test", Map.of())) {
            context.getBeanFactory().registerSingleton("liquibase", new SpringLiquibase());

            assertThat(CracRuntimeInventoryCollector.collect(context).startupDatabaseAccess())
                    .isEmpty();
        }
        assertThat(CracRuntimeInventoryCollector.isInMemoryJdbcUrl("jdbc:hsqldb:mem:test"))
                .isTrue();
        assertThat(CracRuntimeInventoryCollector.isInMemoryJdbcUrl("jdbc:derby:memory:test"))
                .isTrue();
        assertThat(CracRuntimeInventoryCollector.isInMemoryJdbcUrl("jdbc:h2:file:./data/test"))
                .isFalse();
        assertThat(CracRuntimeInventoryCollector.isInMemoryJdbcUrl("jdbc:h2:tcp://localhost/test"))
                .isFalse();
    }

    @Test
    void schemaInitializersReportBundledScriptsAndApplicationScriptsOnlyWhenAlways() {
        for (String mode : List.of("embedded", "always")) {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of("spring.sql.init.mode", mode)));
                HikariDataSource dataSource = new HikariDataSource();
                dataSource.setJdbcUrl("jdbc:postgresql://db.example/app");
                context.registerBean("dataSource", HikariDataSource.class, () -> dataSource);
                context.refresh();
                DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
                context.getBeanFactory()
                        .registerSingleton("batchInitializer", new BundledScriptsInitializer(dataSource, settings));
                context.getBeanFactory()
                        .registerSingleton(
                                "applicationInitializer",
                                new ApplicationDataSourceScriptDatabaseInitializer(dataSource, settings));

                List<String> access =
                        CracRuntimeInventoryCollector.collect(context).startupDatabaseAccess();

                assertThat(access)
                        .anyMatch(text -> text.startsWith("schema initializer with bundled scripts batchInitializer"));
                if ("always".equals(mode)) {
                    assertThat(access).hasSize(2).anyMatch(text -> text.contains("spring.sql.init.mode=always"));
                } else {
                    assertThat(access).hasSize(1);
                }
            }
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
            assertThat(inventory.dataSourceObservations())
                    .singleElement()
                    .asString()
                    .contains("dataSource", "pooling is unverified", "checkpoint handling is unverified");
            assertThat(inventory.connectionPoolBeans()).isEmpty();
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
        Class<?> jedisFactory = Class.forName(
                "org.springframework.data.redis.connection.jedis.JedisConnectionFactory",
                false,
                getClass().getClassLoader());
        assertThat(CracRuntimeInventoryCollector.isKnownManagedType(jedisFactory))
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

    private static AnnotationConfigApplicationContext startupDatabaseContext(
            String jdbcUrl, Map<String, Object> jpaProperties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", HikariDataSource.class, () -> {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(jdbcUrl);
            return dataSource;
        });
        context.refresh();
        // Registered without initialization callbacks: the factory never builds an EntityManagerFactory.
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setJpaPropertyMap(jpaProperties);
        context.getBeanFactory().registerSingleton("entityManagerFactory", factory);
        return context;
    }

    static class BundledScriptsInitializer extends DataSourceScriptDatabaseInitializer {
        BundledScriptsInitializer(DataSource dataSource, DatabaseInitializationSettings settings) {
            super(dataSource, settings);
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
