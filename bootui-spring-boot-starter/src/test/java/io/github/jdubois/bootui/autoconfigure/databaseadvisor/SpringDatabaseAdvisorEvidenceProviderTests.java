package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.datasource.SpringConnectionPoolProvider;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource;
import io.github.jdubois.bootui.engine.hibernate.HibernateApplicationFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettings;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernatePoolObservation;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.DataSourceConfiguration;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * The Spring binding of the driver-evidence SPI over real HikariCP-typed beans (mocked pools): attribution by identity
 * through a SQL Trace proxy and a lazy wrapper, two datasources, URL-only configuration, and optional-bean absence.
 */
class SpringDatabaseAdvisorEvidenceProviderTests {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }

    private static HikariDataSource hikari(Map<String, String> dataSourceProperties) {
        HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getMaximumPoolSize()).thenReturn(10);
        when(pool.getMinimumIdle()).thenReturn(2);
        when(pool.getJdbcUrl()).thenReturn("jdbc:postgresql://secret-host/db?password=examplepass");
        when(pool.getUsername()).thenReturn("dbuser");
        Properties properties = new Properties();
        properties.putAll(dataSourceProperties);
        when(pool.getDataSourceProperties()).thenReturn(properties);
        return pool;
    }

    private static DataSource traced(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                SpringDatabaseAdvisorEvidenceProviderTests.class.getClassLoader(),
                new Class<?>[] {DataSource.class, SqlTracedDataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unwrap" -> target;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static ListableBeanFactory factory(Map<String, DataSource> beans) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(DataSource.class))
                .thenReturn(beans.keySet().toArray(String[]::new));
        when(factory.getBeanNamesForType(HikariDataSource.class))
                .thenReturn(beans.entrySet().stream()
                        .filter(entry -> entry.getValue() instanceof HikariDataSource)
                        .map(Map.Entry::getKey)
                        .toArray(String[]::new));
        beans.forEach((name, bean) -> {
            when(factory.getBean(name, DataSource.class)).thenReturn(bean);
            if (bean instanceof HikariDataSource hikari) {
                when(factory.getBean(name, HikariDataSource.class)).thenReturn(hikari);
            }
        });
        return factory;
    }

    private static HibernatePersistenceUnitObservation unit(String pool, int batchSize) {
        HibernateFactorySettings unknown = HibernateFactorySettings.unknown();
        HibernateFactorySettings settings = new HibernateFactorySettings(
                batchSize,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                unknown.regionFactory(),
                unknown.connectionProvider(),
                unknown.schemaAction(),
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
                unknown.transactionMode());
        return new HibernatePersistenceUnitObservation(
                pool,
                pool,
                List.of(),
                List.of(),
                "7.2",
                settings,
                true,
                new HibernatePoolObservation(pool, ConnectionPoolInfo.HIKARI, false));
    }

    @Test
    void twoDatasourcesGetTheirOwnSanitizedPoolPropertiesAndBatching() {
        HikariDataSource orders = hikari(Map.of("reWriteBatchedInserts", "true", "password", "examplepass"));
        HikariDataSource billing = hikari(Map.of());
        ListableBeanFactory factory = factory(Map.of("orders", orders, "billing", billing));
        HibernateAdvisorObservationSource hibernate = () -> new HibernateAdvisorObservation(
                List.of(unit("orders", 30)), HibernateApplicationFacts.unknown(List.of()), List.of());
        SpringDatabaseAdvisorEvidenceProvider provider = new SpringDatabaseAdvisorEvidenceProvider(
                providerOf(factory),
                providerOf((ConnectionPoolProvider) new SpringConnectionPoolProvider(providerOf(factory))),
                providerOf(hibernate));

        List<DataSourceConfiguration> configurations = provider.dataSourceConfigurations();

        assertThat(configurations).hasSize(2);
        assertThat(configurations)
                .filteredOn(configuration -> configuration.dataSourceName().equals("orders"))
                .singleElement()
                .satisfies(configuration -> {
                    assertThat(configuration.driverProperties())
                            .containsOnly(Map.entry("reWriteBatchedInserts", "true"));
                    assertThat(configuration.driverPropertiesKnown()).isTrue();
                    assertThat(configuration.pool().maximumPoolSize()).isEqualTo(10);
                });
        assertThat(configurations)
                .filteredOn(configuration -> configuration.dataSourceName().equals("billing"))
                .singleElement()
                .satisfies(configuration ->
                        assertThat(configuration.driverProperties()).isEmpty());
        assertThat(configurations.toString()).doesNotContain("examplepass", "secret-host", "dbuser");
        assertThat(provider.jdbcBatching()).containsOnly(Map.entry("orders", JdbcBatching.enabled(30)));
    }

    @Test
    void aTraceProxyAndALazyWrapperAreAttributedToThePoolBehindThem() {
        HikariDataSource pool = hikari(Map.of("rewriteBatchedStatements", "true"));
        DataSource lazy = new LazyConnectionDataSourceProxy(pool);
        ListableBeanFactory factory = factory(Map.of("orders", pool, "lazyOrders", traced(lazy)));
        SpringDatabaseAdvisorEvidenceProvider provider = new SpringDatabaseAdvisorEvidenceProvider(
                providerOf(factory),
                providerOf((ConnectionPoolProvider) new SpringConnectionPoolProvider(providerOf(factory))),
                providerOf(null));

        List<DataSourceConfiguration> configurations = provider.dataSourceConfigurations();

        assertThat(configurations).isNotEmpty();
        assertThat(configurations)
                .allSatisfy(configuration ->
                        assertThat(configuration.driverProperties()).containsKey("rewriteBatchedStatements"));
    }

    @Test
    void urlOnlyConfigurationYieldsKnownEmptyPoolProperties() {
        HikariDataSource pool = hikari(Map.of());
        ListableBeanFactory factory = factory(Map.of("orders", pool));
        SpringDatabaseAdvisorEvidenceProvider provider = new SpringDatabaseAdvisorEvidenceProvider(
                providerOf(factory),
                providerOf((ConnectionPoolProvider) new SpringConnectionPoolProvider(providerOf(factory))),
                providerOf(null));

        assertThat(provider.dataSourceConfigurations()).singleElement().satisfies(configuration -> {
            assertThat(configuration.driverProperties()).isEmpty();
            assertThat(configuration.driverPropertiesKnown()).isTrue();
        });
    }

    @Test
    void withoutHikariOrHibernateBeansThereIsNoPoolAndNoBatching() {
        DataSource plain = mock(DataSource.class);
        ListableBeanFactory factory = factory(Map.of("orders", plain));
        SpringDatabaseAdvisorEvidenceProvider provider =
                new SpringDatabaseAdvisorEvidenceProvider(providerOf(factory), providerOf(null), providerOf(null));

        assertThat(provider.dataSourceConfigurations()).singleElement().satisfies(configuration -> {
            assertThat(configuration.pool()).isNull();
            assertThat(configuration.driverPropertiesKnown()).isFalse();
        });
        assertThat(provider.jdbcBatching()).isEmpty();
    }
}
