package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracingProxies;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Discovery rules for the Spring binding: a wrapped datasource must be introspected exactly once, under one
 * name, because every duplicate would double every finding the Database Advisor reports — and a wrapper that
 * owns the only reference to a pool must still be introspected rather than skipped (issue #924).
 */
class SpringDatabaseAdvisorDataSourceProviderTests {

    private static SpringDatabaseAdvisorDataSourceProvider providerFor(DefaultListableBeanFactory factory) {
        return new SpringDatabaseAdvisorDataSourceProvider(new SimpleObjectProvider(factory));
    }

    @Test
    void discoversEveryDistinctDataSourceBean() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("primaryDataSource", new StubDataSource());
        factory.registerSingleton("reportingDataSource", new StubDataSource());

        List<NamedDataSource> dataSources = providerFor(factory).dataSources();

        assertThat(dataSources)
                .extracting(NamedDataSource::name)
                .containsExactlyInAnyOrder("primaryDataSource", "reportingDataSource");
    }

    @Test
    void skipsSpringDelegatingWrappers() {
        DataSource real = new StubDataSource();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("dataSource", real);
        factory.registerSingleton("delegatingDataSource", new DelegatingDataSource(real));

        assertThat(providerFor(factory).dataSources())
                .extracting(NamedDataSource::name)
                .containsExactly("dataSource");
    }

    /**
     * Spring Boot's {@code spring.datasource.connection-fetch=lazy} replaces the {@code dataSource} bean with a
     * {@link LazyConnectionDataSourceProxy}, so the pool is not a bean at all and skipping the wrapper left the
     * advisor with nothing to inspect.
     */
    @Test
    void introspectsALazyConnectionProxyWhoseTargetIsNotABeanOfItsOwn() {
        DataSource pool = new StubDataSource();
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(pool);
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("dataSource", lazy);

        List<NamedDataSource> dataSources = providerFor(factory).dataSources();

        assertThat(dataSources).hasSize(1);
        assertThat(dataSources.get(0).name()).isEqualTo("dataSource");
        assertThat(dataSources.get(0).dataSource()).isSameAs(lazy);
    }

    @Test
    void collapsesNestedWrappersOverTheSamePoolToOneDatasource() {
        DataSource pool = new StubDataSource();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("dataSource", new DelegatingDataSource(new LazyConnectionDataSourceProxy(pool)));

        assertThat(providerFor(factory).dataSources())
                .extracting(NamedDataSource::name)
                .containsExactly("dataSource");
    }

    @Test
    void introspectsATracedDataSourceAndItsUnderlyingPoolOnlyOnce() {
        DataSource real = new StubDataSource();
        DataSource traced = SqlTracingProxies.wrap(real, recorder());
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("dataSource", traced);
        factory.registerSingleton("rawDataSource", real);

        List<NamedDataSource> dataSources = providerFor(factory).dataSources();

        assertThat(dataSources).hasSize(1);
        assertThat(dataSources.get(0).name()).isEqualTo("dataSource");
    }

    /** SQL Trace inserts its proxy underneath a lazy wrapper, which must not make the pool look like a second one. */
    @Test
    void introspectsATracedPoolBehindALazyProxyOnlyOnce() {
        DataSource pool = new StubDataSource();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("rawDataSource", pool);
        factory.registerSingleton(
                "dataSource", new LazyConnectionDataSourceProxy(SqlTracingProxies.wrap(pool, recorder())));

        assertThat(providerFor(factory).dataSources())
                .extracting(NamedDataSource::name)
                .containsExactly("rawDataSource");
    }

    @Test
    void expandsARoutingDataSourceIntoItsResolvedTargets() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("routingDataSource", routing(new StubDataSource(), new StubDataSource()));

        assertThat(providerFor(factory).dataSources())
                .extracting(NamedDataSource::name)
                .containsExactly("routingDataSource[primary]", "routingDataSource[replica]");
    }

    @Test
    void doesNotDuplicateRoutingTargetsThatAreBeansOfTheirOwn() {
        DataSource primary = new StubDataSource();
        DataSource replica = new StubDataSource();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("primaryDataSource", primary);
        factory.registerSingleton("replicaDataSource", replica);
        factory.registerSingleton("routingDataSource", routing(primary, replica));

        assertThat(providerFor(factory).dataSources())
                .extracting(NamedDataSource::name)
                .containsExactly("primaryDataSource", "replicaDataSource");
    }

    /**
     * A wrapper that will not name its target is reported, so the scan explains a read failure instead of claiming
     * the application has no {@code DataSource} bean.
     */
    @Test
    void reportsAWrapperWhoseTargetCannotBeResolved() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("dataSource", new DelegatingDataSource());

        assertThat(providerFor(factory).dataSources())
                .extracting(NamedDataSource::name)
                .containsExactly("dataSource");
    }

    @Test
    void retainsReadableCandidatesWhenAnotherBeanFails() {
        ListableBeanFactory factory = mock();
        ObjectProvider<ListableBeanFactory> factories = mock();
        when(factories.getIfAvailable()).thenReturn(factory);
        when(factory.getBeanNamesForType(DataSource.class)).thenReturn(new String[] {"broken", "healthy"});
        when(factory.getBean("broken", DataSource.class)).thenThrow(new BeanCreationException("broken"));
        DataSource healthy = new StubDataSource();
        when(factory.getBean("healthy", DataSource.class)).thenReturn(healthy);
        SpringDatabaseAdvisorDataSourceProvider provider = new SpringDatabaseAdvisorDataSourceProvider(factories);

        DatabaseAdvisorDataSourceDiscovery discovery = provider.discover();

        assertThat(discovery.dataSources()).containsExactly(new NamedDataSource("healthy", healthy));
        assertThat(discovery.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.name()).isEqualTo("broken");
            assertThat(failure.message()).contains("could not be resolved");
        });
        assertThatThrownBy(provider::dataSources).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void beanEnumerationFailureIsNotAnEmptyInventory() {
        ListableBeanFactory factory = mock();
        ObjectProvider<ListableBeanFactory> factories = mock();
        when(factories.getIfAvailable()).thenReturn(factory);
        when(factory.getBeanNamesForType(DataSource.class)).thenThrow(new BeanCreationException("enumeration"));

        assertThatThrownBy(new SpringDatabaseAdvisorDataSourceProvider(factories)::discover)
                .isInstanceOf(BeanCreationException.class);
    }

    @Test
    void includesASeparateRoutingDefaultWithoutOpeningConnections() {
        DataSource primary = new StubDataSource();
        DataSource replica = new StubDataSource();
        DataSource fallback = new StubDataSource();
        AbstractRoutingDataSource routing = routing(primary, replica);
        routing.setDefaultTargetDataSource(fallback);
        routing.afterPropertiesSet();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("routing", routing);

        assertThat(providerFor(factory).discover().dataSources())
                .extracting(NamedDataSource::dataSource)
                .containsExactly(primary, replica, fallback);
    }

    private static SqlTraceRecorder recorder() {
        return new SqlTraceRecorder(false, false, false, false, 100, 100, 256, 128, 5);
    }

    private static AbstractRoutingDataSource routing(DataSource primary, DataSource replica) {
        Map<Object, Object> targets = new LinkedHashMap<>();
        targets.put("primary", primary);
        targets.put("replica", replica);
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return "primary";
            }
        };
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(primary);
        routing.afterPropertiesSet();
        return routing;
    }

    /** Minimal {@link ObjectProvider} over a fixed bean factory. */
    private record SimpleObjectProvider(DefaultListableBeanFactory factory)
            implements ObjectProvider<org.springframework.beans.factory.ListableBeanFactory> {

        @Override
        public org.springframework.beans.factory.ListableBeanFactory getObject(Object... args) {
            return factory;
        }

        @Override
        public org.springframework.beans.factory.ListableBeanFactory getObject() {
            return factory;
        }

        @Override
        public org.springframework.beans.factory.ListableBeanFactory getIfAvailable() {
            return factory;
        }

        @Override
        public org.springframework.beans.factory.ListableBeanFactory getIfUnique() {
            return factory;
        }
    }

    private static class StubDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("not used in this test");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
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
