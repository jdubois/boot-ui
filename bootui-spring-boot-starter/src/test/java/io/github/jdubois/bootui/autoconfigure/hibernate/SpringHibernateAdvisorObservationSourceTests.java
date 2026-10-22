package io.github.jdubois.bootui.autoconfigure.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.datasource.SpringConnectionPoolProvider;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettings;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettingsReader;
import io.github.jdubois.bootui.engine.hibernate.HibernateObservationDiagnostic;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernatePoolObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.spi.CacheImplementor;
import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.hibernate.stat.spi.StatisticsImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.mock.env.MockEnvironment;

class SpringHibernateAdvisorObservationSourceTests {
    @Test
    void failingLazyMiddleFactoryDoesNotHideLaterUnitsInEitherOrder() {
        for (List<String> order : List.of(List.of("first", "failed", "last"), List.of("last", "failed", "first"))) {
            DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
            AtomicInteger resolutions = new AtomicInteger();
            for (String name : order) {
                RootBeanDefinition definition = new RootBeanDefinition(EntityManagerFactory.class, () -> {
                    resolutions.incrementAndGet();
                    if ("failed".equals(name)) throw new IllegalStateException("credential-value");
                    return factory(name, 1);
                });
                definition.setLazyInit(true);
                beans.registerBeanDefinition(name, definition);
            }
            SpringHibernateAdvisorObservationSource source = source(beans, new MockEnvironment());
            assertThat(resolutions).hasValue(0);
            HibernateAdvisorObservation observation = source.observe();
            assertThat(resolutions).hasValue(3);
            assertThat(observation.units())
                    .extracting(unit -> unit.label())
                    .containsExactly(order.get(0), order.get(2));
            assertThat(observation.diagnostics())
                    .containsExactly(new HibernateObservationDiagnostic(
                            "unit-2", HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE));
            var report = HibernateScanner.observing(() -> observation, Clock.systemUTC())
                    .scan();
            assertThat(report.scan().status()).isEqualTo("PARTIAL");
            assertThat(report.scan().message()).doesNotContain("credential-value");
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("HIB-CONFIG-004"))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.violationCount()).isEqualTo(2);
                        assertThat(result.sampleViolations()).anyMatch(sample -> sample.startsWith("[first]"));
                        assertThat(result.sampleViolations()).anyMatch(sample -> sample.startsWith("[last]"));
                    });
        }
    }

    @Test
    void observesDivergentNativeOptionsRatherThanEnvironmentAndDeduplicatesUnwrappedIdentity() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        SessionFactoryImplementor first = factory("first", 1);
        SessionFactoryImplementor second = factory("second", 25);
        EntityManagerFactory alias = mock(EntityManagerFactory.class);
        when(alias.unwrap(SessionFactoryImplementor.class)).thenReturn(first);
        beans.registerSingleton("first", first);
        beans.registerSingleton("second", second);
        beans.registerSingleton("alias", alias);
        MockEnvironment environment = new MockEnvironment().withProperty("hibernate.jdbc.batch_size", "999");
        HibernateAdvisorObservation observation = source(beans, environment).observe();
        assertThat(observation.units()).hasSize(2);
        assertThat(observation.units())
                .extracting(unit -> unit.settings().jdbcBatchSize())
                .containsExactly(1, 25);
        assertThat(observation.units())
                .extracting(unit -> unit.settings().defaultBatchFetchSize())
                .containsOnly(16);
        assertThat(observation.units())
                .extracting(unit -> unit.settings().queryCache())
                .containsOnly(true);
        assertThat(observation.units())
                .extracting(unit -> unit.settings().regionFactory())
                .containsOnly(HibernateFactorySettings.RegionFactory.AVAILABLE);
        assertThat(observation.diagnostics()).isEmpty();
        verify(first, times(1)).getSessionFactoryOptions();
    }

    @Test
    void observedDialectOverridesStaleBootstrapDialectWithoutOpeningConnections() {
        SessionFactoryImplementor factory = factory("orders", 25);
        JdbcServices jdbc = mock(JdbcServices.class);
        when(factory.getJdbcServices()).thenReturn(jdbc);
        when(jdbc.getDialect()).thenReturn(mock(org.hibernate.dialect.PostgreSQLDialect.class));
        when(factory.getProperties()).thenReturn(Map.of("hibernate.dialect", "org.hibernate.dialect.OracleDialect"));
        assertThat(HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>())
                        .oracle())
                .isFalse();
        when(jdbc.getDialect()).thenReturn(mock(org.hibernate.dialect.OracleDialect.class));
        assertThat(HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>())
                        .oracle())
                .isTrue();
    }

    @Test
    void effectiveDefaultsAreReadFromNativeOptionsRatherThanConfiguredKeys() {
        SessionFactoryImplementor factory = factory("orders", 25);
        JdbcServices jdbc = mock(JdbcServices.class);
        when(factory.getJdbcServices()).thenReturn(jdbc);
        when(jdbc.getSqlStatementLogger())
                .thenReturn(new org.hibernate.engine.jdbc.spi.SqlStatementLogger(false, true, false, 0));
        HibernateFactorySettings unset = HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>());
        assertThat(unset.lazyLoadOutsideTransaction()).isFalse();
        assertThat(unset.inClausePadding()).isFalse();
        assertThat(unset.sqlComments()).isFalse();
        assertThat(unset.showSql()).isFalse();
        assertThat(unset.formatSql()).isTrue();
        assertThat(unset.slowQueryThreshold()).isZero();
        assertThat(unset.jdbcTimeZoneConfigured()).isFalse();
        assertThat(unset.jdbcFetchSize()).isZero();

        SessionFactoryOptions options = factory.getSessionFactoryOptions();
        when(options.isInitializeLazyStateOutsideTransactionsEnabled()).thenReturn(true);
        when(options.inClauseParameterPaddingEnabled()).thenReturn(true);
        when(options.isCommentsEnabled()).thenReturn(true);
        when(options.getJdbcTimeZone()).thenReturn(java.util.TimeZone.getTimeZone("UTC"));
        when(options.getJdbcFetchSize()).thenReturn(50);
        when(jdbc.getSqlStatementLogger())
                .thenReturn(new org.hibernate.engine.jdbc.spi.SqlStatementLogger(true, false, false, 250));
        List<HibernateObservationDiagnostic> diagnostics = new ArrayList<>();
        HibernateFactorySettings set = HibernateFactorySettingsReader.read(factory, "orders", diagnostics);
        assertThat(set.lazyLoadOutsideTransaction()).isTrue();
        assertThat(set.inClausePadding()).isTrue();
        assertThat(set.sqlComments()).isTrue();
        assertThat(set.showSql()).isTrue();
        assertThat(set.formatSql()).isFalse();
        assertThat(set.slowQueryThreshold()).isEqualTo(250);
        assertThat(set.jdbcTimeZoneConfigured()).isTrue();
        assertThat(set.jdbcFetchSize()).isEqualTo(50);
        assertThat(diagnostics).isEmpty();

        when(options.getJdbcFetchSize()).thenThrow(new IllegalStateException("credential"));
        when(options.getJdbcTimeZone()).thenThrow(new IllegalStateException("credential"));
        HibernateFactorySettings failed = HibernateFactorySettingsReader.read(factory, "orders", diagnostics);
        assertThat(failed.jdbcFetchSize())
                .as("a failed read is unknown, not unset")
                .isNull();
        assertThat(failed.jdbcTimeZoneConfigured()).isNull();
        assertThat(diagnostics)
                .extracting(HibernateObservationDiagnostic::reason)
                .containsExactly(HibernateObservationDiagnostic.Reason.FACTORY_SETTING_UNAVAILABLE);
    }

    @Test
    void ambiguousDomainIsNeverAssignedToTheFirstFactory() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("first", factory("first", 1));
        beans.registerSingleton("second", factory("second", 25));
        beans.registerSingleton("repository", SpringHibernateRepositoryDiscoveryTests.factory(true));
        HibernateAdvisorObservation observation =
                source(beans, new MockEnvironment()).observe();
        assertThat(observation.units())
                .allSatisfy(unit -> assertThat(unit.repositories()).isEmpty());
        assertThat(observation.diagnostics())
                .extracting(HibernateObservationDiagnostic::reason)
                .contains(HibernateObservationDiagnostic.Reason.AMBIGUOUS_REPOSITORY_UNIT);
    }

    @Test
    void failedIndividualGetterKeepsMappingsAndOtherSettingsWithControlledDiagnostic() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        SessionFactoryImplementor factory = factory("orders", 25);
        when(factory.getSessionFactoryOptions().isOrderInsertsEnabled())
                .thenThrow(new NoClassDefFoundError("credential"));
        beans.registerSingleton("factory", factory);
        HibernateAdvisorObservation observation =
                source(beans, new MockEnvironment()).observe();
        assertThat(observation.units()).singleElement().satisfies(unit -> {
            assertThat(unit.entities()).hasSize(1);
            assertThat(unit.settings().jdbcBatchSize()).isEqualTo(25);
            assertThat(unit.settings().orderInserts()).isNull();
            assertThat(unit.settings().orderUpdates()).isTrue();
        });
        assertThat(observation.diagnostics())
                .extracting(HibernateObservationDiagnostic::reason)
                .contains(HibernateObservationDiagnostic.Reason.FACTORY_SETTING_UNAVAILABLE);
        assertThat(observation.diagnostics().toString()).doesNotContain("credential");
    }

    @Test
    void nativeSchemaVocabularyAndScalarAllowlistNeverRetainArbitraryProperties() {
        SessionFactoryImplementor factory = factory("orders", 25);
        when(factory.getProperties())
                .thenReturn(Map.of(
                        "jakarta.persistence.schema-generation.database.action", "create",
                        "hibernate.hbm2ddl.auto", "create",
                        "hibernate.connection.password", "credential",
                        "hibernate.connection.url", "jdbc:secret"));
        HibernateFactorySettings settings = HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>());
        assertThat(settings.schemaAction()).isEqualTo(HibernateFactorySettings.SchemaAction.CREATE_ONLY);
        assertThat(settings.toString()).doesNotContain("credential", "jdbc:secret", "password");
        when(factory.getProperties()).thenReturn(Map.of("hibernate.hbm2ddl.auto", "create"));
        assertThat(HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>())
                        .schemaAction())
                .isEqualTo(HibernateFactorySettings.SchemaAction.DROP_AND_CREATE);
    }

    @Test
    void attributesEachUnitToItsPoolThroughTheConnectionProviderDataSource() throws Exception {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        HikariDataSource ordersPool = hikari("HikariPool-orders", false);
        HikariDataSource billingPool = hikari("HikariPool-billing", true);
        beans.registerSingleton("ordersPool", ordersPool);
        beans.registerSingleton("billingPool", billingPool);
        beans.registerSingleton("orders", factoryBackedBy("orders", traced(ordersPool)));
        beans.registerSingleton("audit", factoryBackedBy("audit", new LazyConnectionDataSourceProxy(ordersPool)));
        beans.registerSingleton("billing", factoryBackedBy("billing", billingPool));

        HibernateAdvisorObservation observation =
                source(beans, new MockEnvironment(), pools(beans)).observe();

        assertThat(observation.units())
                .extracting(HibernatePersistenceUnitObservation::label, HibernatePersistenceUnitObservation::pool)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "orders", new HibernatePoolObservation("ordersPool", "HikariCP", false)),
                        org.assertj.core.groups.Tuple.tuple(
                                "audit", new HibernatePoolObservation("ordersPool", "HikariCP", false)),
                        org.assertj.core.groups.Tuple.tuple(
                                "billing", new HibernatePoolObservation("billingPool", "HikariCP", true)));
        assertThat(observation.diagnostics()).isEmpty();
        verify(ordersPool, never()).getConnection();
    }

    @Test
    void aUnitThatCannotBeAttributedHasNoPoolInsteadOfAGuess() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("pool", hikari("HikariPool-1", false));
        beans.registerSingleton("unmatched", factoryBackedBy("unmatched", mock(DataSource.class)));
        beans.registerSingleton("builtIn", factory("builtIn", 25));

        assertThat(source(beans, new MockEnvironment(), pools(beans)).observe().units())
                .extracting(HibernatePersistenceUnitObservation::pool)
                .containsOnlyNulls();
        assertThat(source(beans, new MockEnvironment(), null).observe().units())
                .extracting(HibernatePersistenceUnitObservation::pool)
                .containsOnlyNulls();
        assertThat(source(beans, new MockEnvironment()).observe().units())
                .extracting(HibernatePersistenceUnitObservation::pool)
                .containsOnlyNulls();
    }

    @Test
    void theUnitPoolSurvivesTheRepositoryAttachmentRebuild() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        HikariDataSource pool = hikari("HikariPool-1", true);
        beans.registerSingleton("pool", pool);
        beans.registerSingleton("orders", factoryBackedBy("orders", pool));
        beans.registerSingleton("repository", SpringHibernateRepositoryDiscoveryTests.factory(true));

        HibernateAdvisorObservation observation =
                source(beans, new MockEnvironment(), pools(beans)).observe();

        assertThat(observation.units()).singleElement().satisfies(unit -> {
            assertThat(unit.repositories()).hasSize(1);
            assertThat(unit.pool()).isEqualTo(new HibernatePoolObservation("pool", "HikariCP", true));
        });
    }

    @Test
    void aScanFlagsTheProviderFlagContradictingAnAutoCommitPoolAndOnlyThen() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("autoCommitPool", hikari("HikariPool-auto", true));
        beans.registerSingleton("manualPool", hikari("HikariPool-manual", false));
        SessionFactoryImplementor contradicted =
                factoryBackedBy("contradicted", (DataSource) beans.getBean("autoCommitPool"));
        when(contradicted.getSessionFactoryOptions().doesConnectionProviderDisableAutoCommit())
                .thenReturn(true);
        SessionFactoryImplementor consistent = factoryBackedBy("consistent", (DataSource) beans.getBean("manualPool"));
        when(consistent.getSessionFactoryOptions().doesConnectionProviderDisableAutoCommit())
                .thenReturn(true);
        SessionFactoryImplementor recommended =
                factoryBackedBy("recommended", (DataSource) beans.getBean("manualPool"));
        SessionFactoryImplementor jta = factoryBackedBy("jta", (DataSource) beans.getBean("autoCommitPool"));
        when(jta.getSessionFactoryOptions().doesConnectionProviderDisableAutoCommit())
                .thenReturn(true);
        HibernatePoolAttributionTests.transactionCoordinator(jta, true);
        beans.registerSingleton("contradicted", contradicted);
        beans.registerSingleton("consistent", consistent);
        beans.registerSingleton("recommended", recommended);
        beans.registerSingleton("jta", jta);
        HibernateAdvisorObservation observation =
                source(beans, new MockEnvironment(), pools(beans)).observe();

        var report =
                HibernateScanner.observing(() -> observation, Clock.systemUTC()).scan();

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("HIB-CONFIG-021"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.violationCount()).isEqualTo(1);
                    assertThat(result.sampleViolations())
                            .singleElement()
                            .asString()
                            .startsWith("[contradicted]")
                            .contains("HikariCP pool autoCommitPool");
                });
        assertThat(report.results())
                .filteredOn(result -> result.id().equals("HIB-CONFIG-008"))
                .singleElement()
                .satisfies(result -> assertThat(result.sampleViolations())
                        .singleElement()
                        .asString()
                        .startsWith("[recommended]"));
        assertThat(report.diagnostics())
                .noneMatch(diagnostic -> diagnostic.source().equals("HIB-CONFIG-008")
                        || diagnostic.source().equals("HIB-CONFIG-021"));
    }

    private static ObjectProvider<ConnectionPoolProvider> pools(DefaultListableBeanFactory beans) {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.beans.factory.ListableBeanFactory> beanFactory = mock(ObjectProvider.class);
        when(beanFactory.getIfAvailable()).thenReturn(beans);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConnectionPoolProvider> pools = mock(ObjectProvider.class);
        when(pools.getIfUnique()).thenReturn(new SpringConnectionPoolProvider(beanFactory));
        return pools;
    }

    private static HikariDataSource hikari(String poolName, boolean autoCommit) {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getPoolName()).thenReturn(poolName);
        when(dataSource.isAutoCommit()).thenReturn(autoCommit);
        return dataSource;
    }

    private static DataSource traced(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                SpringHibernateAdvisorObservationSourceTests.class.getClassLoader(),
                new Class<?>[] {DataSource.class, SqlTracedDataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unwrap" -> target;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static SessionFactoryImplementor factoryBackedBy(String name, DataSource dataSource) {
        SessionFactoryImplementor factory = factory(name, 25);
        ConnectionProvider provider = mock(ConnectionProvider.class);
        when(provider.isUnwrappableAs(DataSource.class)).thenReturn(true);
        when(provider.unwrap(DataSource.class)).thenReturn(dataSource);
        when(factory.getServiceRegistry().getService(ConnectionProvider.class)).thenReturn(provider);
        HibernatePoolAttributionTests.transactionCoordinator(factory, false);
        return factory;
    }

    static SpringHibernateAdvisorObservationSource source(
            DefaultListableBeanFactory beans,
            MockEnvironment environment,
            ObjectProvider<ConnectionPoolProvider> pools) {
        return new SpringHibernateAdvisorObservationSource(beans, environment, pools);
    }

    static SpringHibernateAdvisorObservationSource source(
            DefaultListableBeanFactory beans, MockEnvironment environment) {
        return new SpringHibernateAdvisorObservationSource(beans, environment);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static SessionFactoryImplementor factory(String name, int batch) {
        SessionFactoryImplementor factory = mock(SessionFactoryImplementor.class);
        when(factory.unwrap(SessionFactoryImplementor.class)).thenReturn(factory);
        when(factory.getName()).thenReturn(name);
        SessionFactoryOptions options = mock(SessionFactoryOptions.class);
        when(factory.getSessionFactoryOptions()).thenReturn(options);
        when(options.getJdbcBatchSize()).thenReturn(batch);
        when(options.getDefaultBatchFetchSize()).thenReturn(16);
        when(options.isOrderUpdatesEnabled()).thenReturn(true);
        when(options.isSecondLevelCacheEnabled()).thenReturn(true);
        when(options.isQueryCacheEnabled()).thenReturn(true);
        StatisticsImplementor statistics = mock(StatisticsImplementor.class);
        when(factory.getStatistics()).thenReturn(statistics);
        when(statistics.isStatisticsEnabled()).thenReturn(true);
        CacheImplementor cache = mock(CacheImplementor.class);
        when(factory.getCache()).thenReturn(cache);
        when(cache.getRegionFactory()).thenReturn(mock(RegionFactory.class));
        ServiceRegistryImplementor registry = mock(ServiceRegistryImplementor.class);
        when(factory.getServiceRegistry()).thenReturn(registry);
        when(registry.getService(ConnectionProvider.class)).thenReturn(mock(ConnectionProvider.class));
        when(factory.getProperties()).thenReturn(Map.of("hibernate.hbm2ddl.auto", "none"));
        Metamodel metamodel = mock(Metamodel.class);
        when(factory.getMetamodel()).thenReturn(metamodel);
        EntityType entity = mock(EntityType.class);
        when(entity.getJavaType()).thenReturn(SpringHibernateRepositoryDiscoveryTests.Order.class);
        when(entity.getName()).thenReturn("Order");
        when(entity.getAttributes()).thenReturn(Set.of());
        when(metamodel.getEntities()).thenReturn(Set.of(entity));
        return factory;
    }
}
