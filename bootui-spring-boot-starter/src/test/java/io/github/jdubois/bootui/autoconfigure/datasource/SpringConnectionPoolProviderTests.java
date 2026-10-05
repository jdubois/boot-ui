package io.github.jdubois.bootui.autoconfigure.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

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
}
