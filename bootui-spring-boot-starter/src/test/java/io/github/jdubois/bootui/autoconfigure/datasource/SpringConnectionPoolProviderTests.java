package io.github.jdubois.bootui.autoconfigure.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Tests for the Spring binding of {@code ConnectionPoolProvider}: the HikariCP discovery (direct beans and
 * proxied/wrapped {@code DataSource} beans), the live {@link HikariPoolMXBean} snapshot read, and the
 * closed-pool / no-bean-factory paths. The provider returns the <em>raw</em>, unmasked JDBC URL and username —
 * masking is the engine's job and is covered by {@code ConnectionPoolServiceTests}.
 */
class SpringConnectionPoolProviderTests {

    // Built by concatenation so the userinfo segment is never a literal credential.
    private static final String RAW_JDBC_URL =
            "jdbc:postgresql://" + "app" + ":" + "examplepass" + "@localhost:5432/demo";

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ListableBeanFactory> provider(ListableBeanFactory factory) {
        ObjectProvider<ListableBeanFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(factory);
        return provider;
    }

    private static ListableBeanFactory beanFactoryWith(String beanName, HikariDataSource dataSource) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(HikariDataSource.class)).thenReturn(new String[] {beanName});
        when(factory.getBean(beanName, HikariDataSource.class)).thenReturn(dataSource);
        return factory;
    }

    private static ListableBeanFactory beanFactoryWithProxiedDataSource(String beanName, DataSource dataSource) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(HikariDataSource.class)).thenReturn(new String[0]);
        when(factory.getBeanNamesForType(DataSource.class)).thenReturn(new String[] {beanName});
        when(factory.getBean(beanName, DataSource.class)).thenReturn(dataSource);
        return factory;
    }

    private static HikariDataSource runningDataSource() {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getPoolName()).thenReturn("HikariPool-1");
        when(dataSource.getJdbcUrl()).thenReturn(RAW_JDBC_URL);
        when(dataSource.getUsername()).thenReturn("app");
        when(dataSource.getDriverClassName()).thenReturn("org.postgresql.Driver");
        when(dataSource.getMinimumIdle()).thenReturn(5);
        when(dataSource.getMaximumPoolSize()).thenReturn(10);
        when(dataSource.getConnectionTimeout()).thenReturn(30000L);
        when(dataSource.getIdleTimeout()).thenReturn(600000L);
        when(dataSource.getMaxLifetime()).thenReturn(1800000L);
        when(dataSource.getValidationTimeout()).thenReturn(5000L);
        when(dataSource.getKeepaliveTime()).thenReturn(0L);
        when(dataSource.isReadOnly()).thenReturn(false);
        when(dataSource.isAutoCommit()).thenReturn(true);

        HikariPoolMXBean mxBean = mock(HikariPoolMXBean.class);
        when(mxBean.getActiveConnections()).thenReturn(3);
        when(mxBean.getIdleConnections()).thenReturn(2);
        when(mxBean.getTotalConnections()).thenReturn(5);
        when(mxBean.getThreadsAwaitingConnection()).thenReturn(1);
        when(dataSource.getHikariPoolMXBean()).thenReturn(mxBean);
        return dataSource;
    }

    private static DataSource proxyFor(HikariDataSource target) {
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(DataSource.class);
        proxyFactory.setTarget(target);
        return (DataSource) proxyFactory.getProxy();
    }

    @Test
    void readsRawMetadataAndLiveSnapshot() {
        SpringConnectionPoolProvider provider =
                new SpringConnectionPoolProvider(provider(beanFactoryWith("dataSource", runningDataSource())));

        List<ConnectionPoolInfo> pools = provider.pools();

        assertThat(pools).hasSize(1);
        ConnectionPoolInfo pool = pools.get(0);
        assertThat(pool.beanName()).isEqualTo("dataSource");
        assertThat(pool.poolName()).isEqualTo("HikariPool-1");
        assertThat(pool.jdbcUrl()).isEqualTo(RAW_JDBC_URL);
        assertThat(pool.username()).isEqualTo("app");
        assertThat(pool.driverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(pool.minimumIdle()).isEqualTo(5);
        assertThat(pool.maximumPoolSize()).isEqualTo(10);
        assertThat(pool.validationTimeoutMs()).isEqualTo(5000L);
        assertThat(pool.keepaliveTimeMs()).isZero();
        assertThat(pool.available()).isTrue();
        assertThat(pool.snapshot().active()).isEqualTo(3);
        assertThat(pool.snapshot().idle()).isEqualTo(2);
        assertThat(pool.snapshot().total()).isEqualTo(5);
        assertThat(pool.snapshot().pending()).isEqualTo(1);
    }

    @Test
    void discoversWrappedHikariDataSourceProxy() {
        SpringConnectionPoolProvider provider = new SpringConnectionPoolProvider(
                provider(beanFactoryWithProxiedDataSource("dataSource", proxyFor(runningDataSource()))));

        List<ConnectionPoolInfo> pools = provider.pools();

        assertThat(pools).hasSize(1);
        assertThat(pools.get(0).beanName()).isEqualTo("dataSource");
        assertThat(pools.get(0).poolName()).isEqualTo("HikariPool-1");
        assertThat(pools.get(0).available()).isTrue();
    }

    @Test
    void marksClosedPoolUnavailable() {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getPoolName()).thenReturn("HikariPool-closed");
        when(dataSource.getJdbcUrl()).thenReturn("jdbc:h2:mem:test");
        when(dataSource.getHikariPoolMXBean()).thenReturn(null);
        when(dataSource.isClosed()).thenReturn(true);
        SpringConnectionPoolProvider provider =
                new SpringConnectionPoolProvider(provider(beanFactoryWith("closedDataSource", dataSource)));

        ConnectionPoolInfo pool = provider.pools().get(0);

        assertThat(pool.available()).isFalse();
        assertThat(pool.unavailableReason()).isEqualTo("Pool is closed");
        assertThat(pool.snapshot()).isNull();
    }

    @Test
    void returnsEmptyWhenNoBeanFactory() {
        SpringConnectionPoolProvider provider = new SpringConnectionPoolProvider(provider(null));

        assertThat(provider.pools()).isEmpty();
    }

    // --- poolFor: attribute a datasource to its pool by physical identity ---------------------------------------

    private static ListableBeanFactory beanFactoryWith(
            String firstName, HikariDataSource first, String secondName, HikariDataSource second) {
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(HikariDataSource.class)).thenReturn(new String[] {firstName, secondName});
        when(factory.getBean(firstName, HikariDataSource.class)).thenReturn(first);
        when(factory.getBean(secondName, HikariDataSource.class)).thenReturn(second);
        return factory;
    }

    private static HikariDataSource pool(boolean autoCommit) {
        HikariDataSource dataSource = runningDataSource();
        when(dataSource.isAutoCommit()).thenReturn(autoCommit);
        return dataSource;
    }

    private static DataSource traced(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                SpringConnectionPoolProviderTests.class.getClassLoader(),
                new Class<?>[] {DataSource.class, SqlTracedDataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unwrap" -> target;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    @Test
    void readsOnlyAllowListedDriverPropertiesFromEachPoolsDataSourceProperties() {
        HikariDataSource orders = runningDataSource();
        java.util.Properties ordersProperties = new java.util.Properties();
        ordersProperties.setProperty("reWriteBatchedInserts", "true");
        ordersProperties.setProperty("user", "app");
        ordersProperties.setProperty("password", "examplepass");
        when(orders.getDataSourceProperties()).thenReturn(ordersProperties);
        HikariDataSource billing = runningDataSource();
        java.util.Properties billingProperties = new java.util.Properties();
        billingProperties.setProperty("USEAFFECTEDROWS", "true");
        when(billing.getDataSourceProperties()).thenReturn(billingProperties);
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(HikariDataSource.class)).thenReturn(new String[] {"orders", "billing"});
        when(factory.getBean("orders", HikariDataSource.class)).thenReturn(orders);
        when(factory.getBean("billing", HikariDataSource.class)).thenReturn(billing);

        java.util.Map<String, java.util.Map<String, String>> properties =
                new SpringConnectionPoolProvider(provider(factory)).driverProperties();

        assertThat(properties).containsOnlyKeys("orders", "billing");
        assertThat(properties.get("orders")).containsOnly(java.util.Map.entry("reWriteBatchedInserts", "true"));
        assertThat(properties.get("billing")).containsOnly(java.util.Map.entry("USEAFFECTEDROWS", "true"));
        assertThat(properties.toString()).doesNotContain("examplepass", "user");
    }

    @Test
    void aPoolBuiltOnASuppliedDataSourceOrClassHasNoEntryBecauseItsDriverSettingsLiveElsewhere() {
        HikariDataSource supplied = runningDataSource();
        when(supplied.getDataSource()).thenReturn(mock(DataSource.class));
        HikariDataSource byClass = runningDataSource();
        when(byClass.getDataSourceClassName()).thenReturn("com.mysql.cj.jdbc.MysqlDataSource");
        ListableBeanFactory factory = mock(ListableBeanFactory.class);
        when(factory.getBeanNamesForType(HikariDataSource.class)).thenReturn(new String[] {"supplied", "byClass"});
        when(factory.getBean("supplied", HikariDataSource.class)).thenReturn(supplied);
        when(factory.getBean("byClass", HikariDataSource.class)).thenReturn(byClass);

        assertThat(new SpringConnectionPoolProvider(provider(factory)).driverProperties())
                .isEmpty();
    }

    @Test
    void aPoolWhoseDriverPropertiesCannotBeReadContributesNoEntryAndNoBeanFactoryMeansNone() {
        HikariDataSource broken = runningDataSource();
        when(broken.getDataSourceProperties()).thenThrow(new IllegalStateException("closed"));

        assertThat(new SpringConnectionPoolProvider(provider(beanFactoryWith("broken", broken))).driverProperties())
                .isEmpty();
        assertThat(new SpringConnectionPoolProvider(provider(null)).driverProperties())
                .isEmpty();
    }

    @Test
    void attributesTheHikariPoolItselfWithoutBorrowingAConnection() throws SQLException {
        HikariDataSource hikari = pool(false);
        SpringConnectionPoolProvider provider =
                new SpringConnectionPoolProvider(provider(beanFactoryWith("dataSource", hikari)));

        Optional<ConnectionPoolInfo> match = provider.poolFor(hikari);

        assertThat(match).get().satisfies(pool -> {
            assertThat(pool.beanName()).isEqualTo("dataSource");
            assertThat(pool.implementation()).isEqualTo(ConnectionPoolInfo.HIKARI);
            assertThat(pool.autoCommit()).isFalse();
        });
        verify(hikari, never()).getConnection();
    }

    @Test
    void attributesThroughTheTraceProxyLazyWrapperAndStaticAopProxyAndEvenTheirNesting() {
        HikariDataSource hikari = pool(true);
        SpringConnectionPoolProvider provider =
                new SpringConnectionPoolProvider(provider(beanFactoryWith("dataSource", hikari)));

        for (DataSource wrapped : List.of(
                traced(hikari),
                new LazyConnectionDataSourceProxy(hikari),
                proxyFor(hikari),
                traced(new LazyConnectionDataSourceProxy(traced(hikari))))) {
            assertThat(provider.poolFor(wrapped))
                    .as(wrapped.getClass().getSimpleName())
                    .get()
                    .satisfies(pool -> {
                        assertThat(pool.beanName()).isEqualTo("dataSource");
                        assertThat(pool.autoCommit()).isTrue();
                    });
        }
    }

    @Test
    void twoUnitsSharingOnePoolShareTheAttributionAndTwoPoolsStayApart() {
        HikariDataSource orders = pool(false);
        HikariDataSource billing = pool(true);
        SpringConnectionPoolProvider provider = new SpringConnectionPoolProvider(
                provider(beanFactoryWith("ordersDataSource", orders, "billingDataSource", billing)));

        assertThat(provider.poolFor(orders))
                .get()
                .extracting(ConnectionPoolInfo::beanName)
                .isEqualTo("ordersDataSource");
        assertThat(provider.poolFor(traced(orders)))
                .get()
                .extracting(ConnectionPoolInfo::beanName)
                .isEqualTo("ordersDataSource");
        assertThat(provider.poolFor(billing))
                .get()
                .extracting(ConnectionPoolInfo::beanName)
                .isEqualTo("billingDataSource");
        assertThat(provider.poolFor(billing))
                .get()
                .extracting(ConnectionPoolInfo::autoCommit)
                .isEqualTo(true);
    }

    @Test
    void neverGuessesForAnUnknownRoutingOrNonHikariDataSource() {
        HikariDataSource hikari = pool(false);
        SpringConnectionPoolProvider provider =
                new SpringConnectionPoolProvider(provider(beanFactoryWith("dataSource", hikari)));
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return "key";
            }
        };

        assertThat(provider.poolFor(mock(DataSource.class)))
                .as("a pool that is not a bean")
                .isEmpty();
        assertThat(provider.poolFor(pool(false))).as("another Hikari pool").isEmpty();
        assertThat(provider.poolFor(routing)).as("a routing wrapper").isEmpty();
        assertThat(provider.poolFor(null)).isEmpty();
        assertThat(new SpringConnectionPoolProvider(provider(null)).poolFor(hikari))
                .isEmpty();
    }

    @Test
    void neverCreatesAnUnrelatedLazyDataSourceWhileAttributingAnExistingPool() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        HikariDataSource hikari = pool(false);
        beans.registerSingleton("dataSource", hikari);
        RootBeanDefinition lazy = new RootBeanDefinition(HikariDataSource.class, () -> {
            throw new AssertionError("an unrelated lazy pool must not be created by attribution");
        });
        lazy.setLazyInit(true);
        beans.registerBeanDefinition("lazyPool", lazy);

        Optional<ConnectionPoolInfo> match = new SpringConnectionPoolProvider(provider(beans)).poolFor(hikari);

        assertThat(match).get().extracting(ConnectionPoolInfo::beanName).isEqualTo("dataSource");
    }

    @Test
    void aLazyWrapperWithADistinctReadOnlyTargetIsNotAttributedToItsPrimaryPool() {
        HikariDataSource primary = pool(false);
        HikariDataSource readOnly = pool(true);
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(primary);
        lazy.setReadOnlyDataSource(readOnly);
        SpringConnectionPoolProvider provider =
                new SpringConnectionPoolProvider(provider(beanFactoryWith("primary", primary, "readOnly", readOnly)));

        assertThat(provider.poolFor(lazy)).isEmpty();
        assertThat(provider.poolFor(new LazyConnectionDataSourceProxy(primary)))
                .get()
                .extracting(ConnectionPoolInfo::beanName)
                .isEqualTo("primary");
    }
}
