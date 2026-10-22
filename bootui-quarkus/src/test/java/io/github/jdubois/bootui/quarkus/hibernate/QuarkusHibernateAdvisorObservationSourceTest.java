package io.github.jdubois.bootui.quarkus.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettings;
import io.github.jdubois.bootui.engine.hibernate.HibernateObservationDiagnostic;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernatePoolObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import io.github.jdubois.bootui.quarkus.StubConfig;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.spi.CacheImplementor;
import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.resource.transaction.spi.TransactionCoordinatorBuilder;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.hibernate.stat.spi.StatisticsImplementor;
import org.junit.jupiter.api.Test;

class QuarkusHibernateAdvisorObservationSourceTest {
    @Test
    @SuppressWarnings("unchecked")
    void lazyCdiHandleFailureDoesNotHideLaterFactoriesInEitherOrder() {
        for (boolean reverse : List.of(false, true)) {
            Instance<EntityManagerFactory> factories = mock(Instance.class);
            Instance.Handle<EntityManagerFactory> first = mock(Instance.Handle.class);
            Instance.Handle<EntityManagerFactory> failed = mock(Instance.Handle.class);
            Instance.Handle<EntityManagerFactory> last = mock(Instance.Handle.class);
            EntityManagerFactory firstFactory = factory("first", 1, false);
            EntityManagerFactory lastFactory = factory("last", 1, false);
            when(first.get()).thenReturn(firstFactory);
            when(failed.get()).thenThrow(new IllegalStateException("credential-value"));
            when(last.get()).thenReturn(lastFactory);
            List<Instance.Handle<EntityManagerFactory>> handles =
                    reverse ? List.of(last, failed, first) : List.of(first, failed, last);
            doReturn(handles).when(factories).handles();
            // Reproduce Arc's resolving iterator: next() resolves before entering a foreach body.
            when(factories.iterator())
                    .thenAnswer(invocation ->
                            handles.stream().map(Instance.Handle::get).iterator());
            QuarkusHibernateAdvisorObservationSource source =
                    new QuarkusHibernateAdvisorObservationSource(factories, new StubConfig(Map.of()));
            verifyNoInteractions(first, failed, last);
            HibernateAdvisorObservation observation = source.observe();
            assertThat(observation.units())
                    .extracting(unit -> unit.label())
                    .containsExactly(reverse ? "last" : "first", reverse ? "first" : "last");
            assertThat(observation.diagnostics())
                    .containsExactly(new HibernateObservationDiagnostic(
                            "unit-2", HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE));
            verify(factories, never()).iterator();
            for (Instance.Handle<EntityManagerFactory> handle : handles) {
                verify(handle).get();
                verify(handle, never()).close();
                verify(handle, never()).destroy();
            }
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
    void namedUnitEffectiveDefaultsDoNotInheritDefaultUnitRawProperties() {
        SessionFactoryImplementor first = factory("default", 1, false);
        SessionFactoryImplementor second = factory("inventory", 25, true);
        for (List<EntityManagerFactory> factories : List.of(
                List.<EntityManagerFactory>of(first, second, first),
                List.<EntityManagerFactory>of(second, first, second))) {
            HibernateAdvisorObservation observation = new QuarkusHibernateAdvisorObservationSource(
                            instances(factories),
                            new StubConfig(Map.of(
                                    "quarkus.hibernate-orm.jdbc.statement-batch-size",
                                    "999",
                                    "spring.jpa.open-in-view",
                                    "true")))
                    .observe();
            assertThat(observation.units()).hasSize(2);
            assertThat(observation.diagnostics()).isEmpty();
            assertThat(observation.units()).allSatisfy(unit -> {
                assertThat(unit.settings().defaultBatchFetchSize()).isEqualTo(16);
                assertThat(unit.repositories()).isEmpty();
                assertThat(unit.enhancementVerified()).isTrue();
                assertThat(unit.settings().jdbcBatchSize())
                        .isEqualTo(unit.label().equals("inventory") ? 25 : 1);
                assertThat(unit.settings().queryCache()).isEqualTo(unit.label().equals("inventory"));
            });
        }
    }

    @Test
    void panacheEnhancementUsesBuildMetadataNotApiClasspathPresence() {
        for (boolean verified : List.of(false, true)) {
            HibernateAdvisorObservation observation = new QuarkusHibernateAdvisorObservationSource(
                            instances(List.of(factory("orders", 25, false))),
                            new StubConfig(Map.of(
                                    "bootui.internal.hibernate-panache-enhancement", Boolean.toString(verified))))
                    .observe();
            assertThat(observation.application().panacheEnhancementVerified()).isEqualTo(verified);
        }
    }

    @Test
    void nativeJakartaCreateIsCreateOnlyAndDropAndCreateIsDestructive() {
        SessionFactoryImplementor first = factory("first", 1, false);
        SessionFactoryImplementor second = factory("second", 25, true);
        when(first.getProperties())
                .thenReturn(Map.of("jakarta.persistence.schema-generation.database.action", "create"));
        when(second.getProperties())
                .thenReturn(Map.of("jakarta.persistence.schema-generation.database.action", "drop-and-create"));
        HibernateAdvisorObservation observation = new QuarkusHibernateAdvisorObservationSource(
                        instances(List.of(first, second)), new StubConfig(Map.of()))
                .observe();
        assertThat(observation.units())
                .extracting(unit -> unit.settings().schemaAction())
                .containsExactly(
                        HibernateFactorySettings.SchemaAction.CREATE_ONLY,
                        HibernateFactorySettings.SchemaAction.DROP_AND_CREATE);
    }

    @Test
    void failingFactoryKeepsOtherFactoryAndSanitizesDiagnostic() {
        SessionFactoryImplementor failed = factory("failed", 1, false);
        when(failed.getSessionFactoryOptions().getJdbcBatchSize()).thenThrow(new IllegalStateException("credential"));
        HibernateAdvisorObservation observation = new QuarkusHibernateAdvisorObservationSource(
                        instances(List.of(failed, factory("readable", 25, true))), new StubConfig(Map.of()))
                .observe();
        assertThat(observation.units()).hasSize(2);
        assertThat(observation.units().get(0).entities()).hasSize(1);
        assertThat(observation.units().get(0).settings().jdbcBatchSize()).isNull();
        assertThat(observation.units().get(1).settings().jdbcBatchSize()).isEqualTo(25);
        assertThat(observation.diagnostics())
                .extracting(HibernateObservationDiagnostic::reason)
                .contains(HibernateObservationDiagnostic.Reason.FACTORY_SETTING_UNAVAILABLE);
        assertThat(observation.diagnostics().toString()).doesNotContain("credential");
    }

    @Test
    void attributesUnitsToTheirAgroalPoolsThroughTheConnectionProviderDataSource() {
        DataSource shared = dataSource();
        DataSource other = dataSource();
        SessionFactoryImplementor orders = factory("orders", 25, false);
        SessionFactoryImplementor audit = factory("audit", 25, false);
        SessionFactoryImplementor billing = factory("billing", 25, false);
        SessionFactoryImplementor unmatched = factory("unmatched", 25, false);
        SessionFactoryImplementor builtIn = factory("builtin", 25, false);
        backedBy(orders, tracing(shared));
        backedBy(audit, shared);
        backedBy(billing, other);
        backedBy(unmatched, dataSource());
        ConnectionPoolProvider pools = new ConnectionPoolProvider() {
            @Override
            public List<ConnectionPoolInfo> pools() {
                return List.of();
            }

            @Override
            public Optional<ConnectionPoolInfo> poolFor(DataSource candidate) {
                DataSource physical = candidate instanceof SqlTracedDataSource ? tracedTarget(candidate) : candidate;
                return physical == shared
                        ? Optional.of(info("default", false))
                        : physical == other ? Optional.of(info("billing", true)) : Optional.empty();
            }
        };

        HibernateAdvisorObservation observation = new QuarkusHibernateAdvisorObservationSource(
                        instances(List.of(orders, audit, billing, unmatched, builtIn)),
                        resolvable(pools),
                        new StubConfig(Map.of()))
                .observe();

        assertThat(observation.units())
                .extracting(HibernatePersistenceUnitObservation::label, HibernatePersistenceUnitObservation::pool)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "orders", new HibernatePoolObservation("default", "Agroal", false)),
                        org.assertj.core.groups.Tuple.tuple(
                                "audit", new HibernatePoolObservation("default", "Agroal", false)),
                        org.assertj.core.groups.Tuple.tuple(
                                "billing", new HibernatePoolObservation("billing", "Agroal", true)),
                        org.assertj.core.groups.Tuple.tuple("unmatched", null),
                        org.assertj.core.groups.Tuple.tuple("builtin", null));
        assertThat(observation.diagnostics()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAResolvablePoolProviderNoUnitIsAttributed() {
        SessionFactoryImplementor orders = factory("orders", 25, false);
        backedBy(orders, dataSource());
        Instance<ConnectionPoolProvider> unsatisfied = mock(Instance.class);
        when(unsatisfied.isResolvable()).thenReturn(false);
        Instance<ConnectionPoolProvider> failing = mock(Instance.class);
        when(failing.isResolvable()).thenReturn(true);
        when(failing.get()).thenThrow(new IllegalStateException("credential"));

        for (Instance<ConnectionPoolProvider> pools : java.util.Arrays.asList(unsatisfied, failing, null)) {
            assertThat(new QuarkusHibernateAdvisorObservationSource(
                                    instances(List.of(orders)), pools, new StubConfig(Map.of()))
                            .observe()
                            .units())
                    .singleElement()
                    .satisfies(unit -> assertThat(unit.pool()).isNull());
        }
    }

    @Test
    void readsTheTransactionModeSoQuarkusJtaUnitsAreNeverTreatedAsResourceLocal() {
        SessionFactoryImplementor jta = factory("jta", 25, false);
        SessionFactoryImplementor unknown = factory("unknown", 25, false);
        TransactionCoordinatorBuilder builder = mock(TransactionCoordinatorBuilder.class);
        when(builder.isJta()).thenReturn(true);
        when(jta.getServiceRegistry().getService(TransactionCoordinatorBuilder.class))
                .thenReturn(builder);

        HibernateAdvisorObservation observation = new QuarkusHibernateAdvisorObservationSource(
                        instances(List.of(jta, unknown)), new StubConfig(Map.of()))
                .observe();

        assertThat(observation.units())
                .extracting(unit -> unit.settings().transactionMode())
                .containsExactly(
                        HibernateFactorySettings.TransactionMode.JTA, HibernateFactorySettings.TransactionMode.UNKNOWN);
    }

    private static ConnectionPoolInfo info(String name, boolean autoCommit) {
        return new ConnectionPoolInfo(
                name,
                name,
                ConnectionPoolInfo.AGROAL,
                "jdbc:secret",
                "user",
                null,
                1,
                2,
                null,
                null,
                null,
                null,
                null,
                null,
                autoCommit,
                true,
                null,
                null);
    }

    @SuppressWarnings("unchecked")
    private static Instance<ConnectionPoolProvider> resolvable(ConnectionPoolProvider provider) {
        Instance<ConnectionPoolProvider> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(provider);
        return instance;
    }

    private static void backedBy(SessionFactoryImplementor factory, DataSource dataSource) {
        ConnectionProvider provider = mock(ConnectionProvider.class);
        when(provider.isUnwrappableAs(DataSource.class)).thenReturn(true);
        when(provider.unwrap(DataSource.class)).thenReturn(dataSource);
        when(factory.getServiceRegistry().getService(ConnectionProvider.class)).thenReturn(provider);
        TransactionCoordinatorBuilder builder = mock(TransactionCoordinatorBuilder.class);
        when(factory.getServiceRegistry().getService(TransactionCoordinatorBuilder.class))
                .thenReturn(builder);
    }

    private static DataSource dataSource() {
        return mock(DataSource.class);
    }

    private static DataSource tracedTarget(DataSource traced) {
        try {
            return traced.unwrap(DataSource.class);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static DataSource tracing(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                QuarkusHibernateAdvisorObservationSourceTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class, SqlTracedDataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unwrap" -> target;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    @SuppressWarnings("unchecked")
    private static Instance<EntityManagerFactory> instances(List<EntityManagerFactory> values) {
        Instance<EntityManagerFactory> instances = mock(Instance.class);
        List<Instance.Handle<EntityManagerFactory>> handles = values.stream()
                .map(factory -> {
                    Instance.Handle<EntityManagerFactory> handle = mock(Instance.Handle.class);
                    when(handle.get()).thenReturn(factory);
                    return handle;
                })
                .toList();
        doReturn(handles).when(instances).handles();
        return instances;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SessionFactoryImplementor factory(String name, int batch, boolean cacheEnabled) {
        SessionFactoryImplementor factory = mock(SessionFactoryImplementor.class);
        when(factory.unwrap(SessionFactoryImplementor.class)).thenReturn(factory);
        when(factory.getName()).thenReturn(name);
        SessionFactoryOptions options = mock(SessionFactoryOptions.class);
        when(factory.getSessionFactoryOptions()).thenReturn(options);
        when(options.getJdbcBatchSize()).thenReturn(batch);
        when(options.getDefaultBatchFetchSize()).thenReturn(16);
        when(options.isSecondLevelCacheEnabled()).thenReturn(cacheEnabled);
        when(options.isQueryCacheEnabled()).thenReturn(cacheEnabled);
        when(factory.getStatistics()).thenReturn(mock(StatisticsImplementor.class));
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
        when(entity.getJavaType()).thenReturn(Order.class);
        when(entity.getName()).thenReturn("Order");
        when(entity.getAttributes()).thenReturn(Set.of());
        when(metamodel.getEntities()).thenReturn(Set.of(entity));
        return factory;
    }

    @Entity
    static class Order {
        @Id
        Long id;
    }
}
