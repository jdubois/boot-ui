package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link HikariController}.
 *
 * <p>Covers the pools list (masked metadata and a live snapshot), the
 * closed-pool unavailable path, the snapshot endpoint, and the empty-context
 * case. The controller is read-only, so the tests only stub getters and the
 * pool MXBean counters.</p>
 */
class HikariControllerTests {

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
    void poolsReturnsMaskedMetadataAndSnapshot() throws Exception {
        ListableBeanFactory factory = beanFactoryWith("dataSource", runningDataSource());
        MockMvc mvc = standaloneSetup(new HikariController(provider(factory), new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/database-connection-pools/pools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hikariPresent").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pools[0].beanName").value("dataSource"))
                .andExpect(jsonPath("$.pools[0].poolName").value("HikariPool-1"))
                .andExpect(jsonPath("$.pools[0].jdbcUrl").value("jdbc:postgresql://******@localhost:5432/demo"))
                .andExpect(jsonPath("$.pools[0].username").value("******"))
                .andExpect(jsonPath("$.pools[0].driverClassName").value("org.postgresql.Driver"))
                .andExpect(jsonPath("$.pools[0].minimumIdle").value(5))
                .andExpect(jsonPath("$.pools[0].maximumPoolSize").value(10))
                .andExpect(jsonPath("$.pools[0].available").value(true))
                .andExpect(jsonPath("$.pools[0].snapshot.active").value(3))
                .andExpect(jsonPath("$.pools[0].snapshot.idle").value(2))
                .andExpect(jsonPath("$.pools[0].snapshot.total").value(5))
                .andExpect(jsonPath("$.pools[0].snapshot.pending").value(1));
    }

    @Test
    void poolsDiscoverWrappedHikariDataSourceProxy() throws Exception {
        HikariDataSource target = runningDataSource();
        ListableBeanFactory factory = beanFactoryWithProxiedDataSource("dataSource", proxyFor(target));
        MockMvc mvc = standaloneSetup(new HikariController(provider(factory), new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/database-connection-pools/pools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hikariPresent").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pools[0].beanName").value("dataSource"))
                .andExpect(jsonPath("$.pools[0].poolName").value("HikariPool-1"))
                .andExpect(jsonPath("$.pools[0].available").value(true))
                .andExpect(jsonPath("$.pools[0].snapshot.active").value(3));

        mvc.perform(get("/bootui/api/database-connection-pools/pools/HikariPool-1/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(3));
    }

    @Test
    void poolsExposeRawMetadataWhenExposureIsFull() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.FULL);
        ListableBeanFactory factory = beanFactoryWith("dataSource", runningDataSource());
        MockMvc mvc = standaloneSetup(new HikariController(provider(factory), properties))
                .build();

        mvc.perform(get("/bootui/api/database-connection-pools/pools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pools[0].jdbcUrl").value(RAW_JDBC_URL))
                .andExpect(jsonPath("$.pools[0].username").value("app"));
    }

    @Test
    void poolsMarkClosedPoolUnavailable() throws Exception {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getPoolName()).thenReturn("HikariPool-closed");
        when(dataSource.getJdbcUrl()).thenReturn("jdbc:h2:mem:test");
        when(dataSource.getHikariPoolMXBean()).thenReturn(null);
        when(dataSource.isClosed()).thenReturn(true);
        ListableBeanFactory factory = beanFactoryWith("closedDataSource", dataSource);
        MockMvc mvc = standaloneSetup(new HikariController(provider(factory), new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/database-connection-pools/pools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pools[0].available").value(false))
                .andExpect(jsonPath("$.pools[0].unavailableReason").value("Pool is closed"))
                .andExpect(jsonPath("$.pools[0].snapshot").doesNotExist());
    }

    @Test
    void snapshotReturnsCountsForKnownPool() throws Exception {
        ListableBeanFactory factory = beanFactoryWith("dataSource", runningDataSource());
        MockMvc mvc = standaloneSetup(new HikariController(provider(factory), new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/database-connection-pools/pools/HikariPool-1/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(3))
                .andExpect(jsonPath("$.idle").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.pending").value(1));
    }

    @Test
    void snapshotReturnsNotFoundForUnknownPool() throws Exception {
        ListableBeanFactory factory = beanFactoryWith("dataSource", runningDataSource());
        MockMvc mvc = standaloneSetup(new HikariController(provider(factory), new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/database-connection-pools/pools/does-not-exist/snapshot"))
                .andExpect(status().isNotFound());
    }

    @Test
    void poolsReportEmptyWhenNoBeanFactory() throws Exception {
        MockMvc mvc = standaloneSetup(new HikariController(provider(null), new BootUiProperties()))
                .build();

        mvc.perform(get("/bootui/api/database-connection-pools/pools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hikariPresent").value(true))
                .andExpect(jsonPath("$.total").value(0));
    }
}
