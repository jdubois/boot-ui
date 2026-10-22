package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateApplicationFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettings;
import io.github.jdubois.bootui.engine.hibernate.HibernateObservationDiagnostic;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernatePoolObservation;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.DataSourceConfiguration;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DefaultDatabaseAdvisorEvidenceProviderTests {

    private final DataSource orders = new StubDataSource();
    private final DataSource billing = new StubDataSource();

    // --- configurations -------------------------------------------------------------------------------------------

    @Test
    void attributesEachDatasourceToItsPoolByIdentityAndSanitizesTheSettings() {
        FakePools pools = new FakePools()
                .pool(orders, pool("ordersPool"))
                .pool(billing, pool("billingPool"))
                .properties("ordersPool", Map.of("reWriteBatchedInserts", "true", "password", "hunter2"))
                .properties("billingPool", Map.of("useAffectedRows", "true", "user", "billing"));
        DefaultDatabaseAdvisorEvidenceProvider provider = provider(
                List.of(new NamedDataSource("orders", orders), new NamedDataSource("billing", billing)), pools, null);

        List<DataSourceConfiguration> configurations = provider.dataSourceConfigurations();

        assertThat(configurations).hasSize(2);
        DataSourceConfiguration ordersConfiguration = configurationOf(configurations, "orders");
        assertThat(ordersConfiguration.driverProperties()).containsOnly(Map.entry("reWriteBatchedInserts", "true"));
        assertThat(ordersConfiguration.pool().implementation()).isEqualTo(ConnectionPoolInfo.HIKARI);
        assertThat(ordersConfiguration.pool().maximumPoolSize()).isEqualTo(10);
        assertThat(ordersConfiguration.pool().connectionTimeoutMs()).isEqualTo(30000L);
        assertThat(configurationOf(configurations, "billing").driverProperties())
                .containsOnly(Map.entry("useAffectedRows", "true"));
    }

    @Test
    void theConfigurationNeverCarriesTheUrlUserNameOrBeanName() {
        FakePools pools = new FakePools().pool(orders, pool("ordersPool"));

        DataSourceConfiguration configuration = provider(List.of(new NamedDataSource("orders", orders)), pools, null)
                .dataSourceConfigurations()
                .get(0);

        assertThat(configuration.toString())
                .doesNotContain("secret-host", "hunter2", "dbuser", "ordersPool", "org.postgresql.Driver");
    }

    @Test
    void aWrapperNamedDifferentlyFromItsPoolIsStillAttributed() {
        DataSource lazyWrapper = new StubDataSource();
        FakePools pools = new FakePools()
                .pool(lazyWrapper, pool("ordersPool"))
                .properties("ordersPool", Map.of("reWriteBatchedInserts", "true"));

        DataSourceConfiguration configuration = provider(
                        List.of(new NamedDataSource("lazyOrders", lazyWrapper)), pools, null)
                .dataSourceConfigurations()
                .get(0);

        assertThat(configuration.dataSourceName()).isEqualTo("lazyOrders");
        assertThat(configuration.driverProperties()).containsKey("reWriteBatchedInserts");
    }

    @Test
    void withoutAPoolProviderThePoolIsNullAndTheDriverPropertiesAreEmpty() {
        DataSourceConfiguration configuration = provider(List.of(new NamedDataSource("orders", orders)), null, null)
                .dataSourceConfigurations()
                .get(0);

        assertThat(configuration.pool()).isNull();
        assertThat(configuration.driverProperties()).isEmpty();
    }

    @Test
    void aDatasourceTheProviderCannotAttributeHasNoPoolAndAFailingLookupIsNotAGuess() {
        FakePools pools = new FakePools().failing(billing).pool(orders, pool("ordersPool"));
        List<DataSourceConfiguration> configurations = provider(
                        List.of(new NamedDataSource("orders", orders), new NamedDataSource("billing", billing)),
                        pools,
                        null)
                .dataSourceConfigurations();

        assertThat(configurationOf(configurations, "billing").pool()).isNull();
        assertThat(configurationOf(configurations, "orders").pool()).isNotNull();
    }

    @Test
    void aNameSharedByTwoDatasourcesYieldsNoConfigurationForEither() {
        FakePools pools = new FakePools().pool(orders, pool("a")).pool(billing, pool("b"));

        assertThat(provider(
                                List.of(new NamedDataSource("dup", orders), new NamedDataSource("dup", billing)),
                                pools,
                                null)
                        .dataSourceConfigurations())
                .isEmpty();
    }

    @Test
    void aThirdPartyProviderCannotLeakACredentialThroughTheDriverProperties() {
        FakePools pools = new FakePools()
                .pool(orders, pool("ordersPool"))
                .properties("ordersPool", Map.of("password", "hunter2", "REWRITEBATCHEDSTATEMENTS", " true "));

        DataSourceConfiguration configuration = provider(List.of(new NamedDataSource("orders", orders)), pools, null)
                .dataSourceConfigurations()
                .get(0);

        assertThat(configuration.driverProperties()).containsOnly(Map.entry("REWRITEBATCHEDSTATEMENTS", " true "));
    }

    @Test
    void driverPropertiesAreKnownOnlyWhenThePoolWasAttributedAndItsPropertiesWereReported() {
        FakePools pools = new FakePools()
                .pool(orders, pool("ordersPool"))
                .properties("ordersPool", Map.of())
                .pool(billing, pool("billingPool"));
        List<DataSourceConfiguration> configurations = provider(
                        List.of(
                                new NamedDataSource("orders", orders),
                                new NamedDataSource("billing", billing),
                                new NamedDataSource("other", new StubDataSource())),
                        pools,
                        null)
                .dataSourceConfigurations();

        assertThat(configurationOf(configurations, "orders").driverPropertiesKnown())
                .isTrue();
        assertThat(configurationOf(configurations, "billing").driverPropertiesKnown())
                .isFalse();
        assertThat(configurationOf(configurations, "other").driverPropertiesKnown())
                .isFalse();
    }

    // --- batching ---------------------------------------------------------------------------------------------------

    @Test
    void batchingIsEnabledWithTheBatchSizeOfTheUnitOnThePool() {
        HibernateAdvisorObservation observation = observation(List.of(unit("a", 30, "ordersPool")), List.of());

        assertThat(DefaultDatabaseAdvisorEvidenceProvider.batchingByPool(observation))
                .containsOnly(Map.entry("ordersPool", JdbcBatching.enabled(30)));
    }

    @Test
    void severalUnitsOnOnePoolAreEnabledWithTheSmallestBatchSizeWhenAnyBatches() {
        HibernateAdvisorObservation observation = observation(
                List.of(unit("a", 50, "ordersPool"), unit("b", 20, "ordersPool"), unit("c", 1, "ordersPool")),
                List.of());

        assertThat(DefaultDatabaseAdvisorEvidenceProvider.batchingByPool(observation))
                .containsOnly(Map.entry("ordersPool", JdbcBatching.enabled(20)));
    }

    @Test
    void batchingIsDisabledOnlyWhenEveryUnitOnThePoolIsKnownNotToBatch() {
        HibernateAdvisorObservation observation =
                observation(List.of(unit("a", 1, "ordersPool"), unit("b", 0, "ordersPool")), List.of());

        assertThat(DefaultDatabaseAdvisorEvidenceProvider.batchingByPool(observation))
                .containsOnly(Map.entry("ordersPool", JdbcBatching.disabled()));
    }

    @Test
    void anUnreadableBatchSizeIsUnknownNeverDisabled() {
        HibernateAdvisorObservation observation =
                observation(List.of(unit("a", null, "ordersPool"), unit("b", 1, "ordersPool")), List.of());

        assertThat(DefaultDatabaseAdvisorEvidenceProvider.batchingByPool(observation))
                .containsOnly(Map.entry("ordersPool", JdbcBatching.unknown()));
    }

    @Test
    void aUnitNotAttributedToAnyPoolMayBeUsingThisOneSoOffBecomesUnknown() {
        HibernateAdvisorObservation observation =
                observation(List.of(unit("a", 1, "ordersPool"), unit("b", 30, null)), List.of());

        assertThat(DefaultDatabaseAdvisorEvidenceProvider.batchingByPool(observation))
                .containsOnly(Map.entry("ordersPool", JdbcBatching.unknown()));
    }

    @Test
    void anUnreadableFactoryMayBeUsingThisPoolSoOffBecomesUnknownButOnStaysOn() {
        HibernateObservationDiagnostic unreadable =
                new HibernateObservationDiagnostic("unit-9", HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE);

        assertThat(DefaultDatabaseAdvisorEvidenceProvider.batchingByPool(
                        observation(List.of(unit("a", 1, "ordersPool")), List.of(unreadable))))
                .containsOnly(Map.entry("ordersPool", JdbcBatching.unknown()));
        assertThat(DefaultDatabaseAdvisorEvidenceProvider.batchingByPool(
                        observation(List.of(unit("a", 30, "ordersPool")), List.of(unreadable))))
                .containsOnly(Map.entry("ordersPool", JdbcBatching.enabled(30)));
    }

    @Test
    void aUnitWhosePoolHasNoNameIsAHiddenUnit() {
        HibernatePersistenceUnitObservation nameless = new HibernatePersistenceUnitObservation(
                "n",
                "n",
                List.of(),
                List.of(),
                "7.2",
                settings(30),
                true,
                new HibernatePoolObservation(null, "HikariCP", false));

        assertThat(DefaultDatabaseAdvisorEvidenceProvider.batchingByPool(
                        observation(List.of(unit("a", 1, "ordersPool"), nameless), List.of())))
                .containsOnly(Map.entry("ordersPool", JdbcBatching.unknown()));
    }

    @Test
    void aMetamodelDiagnosticDoesNotHideAnOffUnit() {
        HibernateObservationDiagnostic metamodel =
                new HibernateObservationDiagnostic("a", HibernateObservationDiagnostic.Reason.METAMODEL_UNAVAILABLE);

        assertThat(DefaultDatabaseAdvisorEvidenceProvider.batchingByPool(
                        observation(List.of(unit("a", 1, "ordersPool")), List.of(metamodel))))
                .containsOnly(Map.entry("ordersPool", JdbcBatching.disabled()));
    }

    @Test
    void batchingIsKeyedByDatasourceNameAndAbsentWhenNoUnitUsesTheDatasource() {
        FakePools pools = new FakePools().pool(orders, pool("ordersPool")).pool(billing, pool("billingPool"));
        DefaultDatabaseAdvisorEvidenceProvider provider = provider(
                List.of(new NamedDataSource("orders", orders), new NamedDataSource("billing", billing)),
                pools,
                () -> observation(List.of(unit("a", 30, "ordersPool")), List.of()));

        assertThat(provider.jdbcBatching()).containsOnly(Map.entry("orders", JdbcBatching.enabled(30)));
    }

    @Test
    void withoutHibernateOrAPoolProviderBatchingIsEmptyNotDisabled() {
        assertThat(provider(List.of(new NamedDataSource("orders", orders)), null, null)
                        .jdbcBatching())
                .isEmpty();
        assertThat(provider(
                                List.of(new NamedDataSource("orders", orders)),
                                null,
                                () -> observation(List.of(unit("a", 30, "ordersPool")), List.of()))
                        .jdbcBatching())
                .isEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static DefaultDatabaseAdvisorEvidenceProvider provider(
            List<NamedDataSource> dataSources,
            ConnectionPoolProvider pools,
            io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource hibernate) {
        return new DefaultDatabaseAdvisorEvidenceProvider(() -> dataSources, () -> pools, () -> hibernate);
    }

    private static DataSourceConfiguration configurationOf(List<DataSourceConfiguration> all, String name) {
        return all.stream()
                .filter(configuration -> configuration.dataSourceName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static ConnectionPoolInfo pool(String beanName) {
        return new ConnectionPoolInfo(
                beanName,
                beanName,
                ConnectionPoolInfo.HIKARI,
                "jdbc:postgresql://secret-host/db?password=hunter2",
                "dbuser",
                "org.postgresql.Driver",
                2,
                10,
                30000L,
                600000L,
                1800000L,
                5000L,
                0L,
                false,
                false,
                true,
                null,
                null);
    }

    private static HibernateAdvisorObservation observation(
            List<HibernatePersistenceUnitObservation> units, List<HibernateObservationDiagnostic> diagnostics) {
        return new HibernateAdvisorObservation(units, HibernateApplicationFacts.unknown(List.of()), diagnostics);
    }

    private static HibernatePersistenceUnitObservation unit(String label, Integer batchSize, String pool) {
        return new HibernatePersistenceUnitObservation(
                label,
                label,
                List.of(),
                List.of(),
                "7.2",
                settings(batchSize),
                true,
                pool == null ? null : new HibernatePoolObservation(pool, ConnectionPoolInfo.HIKARI, false));
    }

    private static HibernateFactorySettings settings(Integer batchSize) {
        return new HibernateFactorySettings(
                batchSize,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                HibernateFactorySettings.RegionFactory.UNKNOWN,
                HibernateFactorySettings.ConnectionProvider.UNKNOWN,
                HibernateFactorySettings.SchemaAction.UNKNOWN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                HibernateFactorySettings.TransactionMode.UNKNOWN);
    }

    private static final class FakePools implements ConnectionPoolProvider {
        private final Map<DataSource, ConnectionPoolInfo> byDataSource = new IdentityHashMap<>();
        private final Map<DataSource, Boolean> failing = new IdentityHashMap<>();
        private final java.util.Map<String, Map<String, String>> properties = new java.util.HashMap<>();

        FakePools pool(DataSource dataSource, ConnectionPoolInfo info) {
            byDataSource.put(dataSource, info);
            return this;
        }

        FakePools failing(DataSource dataSource) {
            failing.put(dataSource, true);
            return this;
        }

        FakePools properties(String pool, Map<String, String> values) {
            properties.put(pool, values);
            return this;
        }

        @Override
        public List<ConnectionPoolInfo> pools() {
            return List.copyOf(byDataSource.values());
        }

        @Override
        public Optional<ConnectionPoolInfo> poolFor(DataSource dataSource) {
            if (failing.containsKey(dataSource)) {
                throw new IllegalStateException("boom");
            }
            return Optional.ofNullable(byDataSource.get(dataSource));
        }

        @Override
        public Map<String, Map<String, String>> driverProperties() {
            return properties;
        }
    }

    private static final class StubDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return iface.cast(this);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
