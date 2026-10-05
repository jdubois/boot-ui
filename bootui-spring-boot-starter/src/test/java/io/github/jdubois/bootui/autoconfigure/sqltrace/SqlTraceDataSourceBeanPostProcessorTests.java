package io.github.jdubois.bootui.autoconfigure.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Verifies that {@link SqlTraceDataSourceBeanPostProcessor} preserves a vendor-specific
 * {@code DataSource} interface (such as Oracle UCP's {@code PoolDataSource}) on the traced proxy, so
 * by-type/by-interface injection of the vendor contract keeps resolving after SQL tracing wraps the
 * bean (see issue #762) — and that a Spring wrapper owning the only reference to a pool still gets that
 * pool traced, in place, without replacing the bean (see issue #924).
 */
class SqlTraceDataSourceBeanPostProcessorTests {

    @Test
    void tracedProxyStillImplementsVendorSpecificDataSourceInterface() {
        SqlTraceRecorder recorder = enabledRecorder();
        SqlTraceDataSourceBeanPostProcessor bpp = new SqlTraceDataSourceBeanPostProcessor(provider(recorder));

        Object result = bpp.postProcessAfterInitialization(new VendorPoolDataSource(), "workerDataSource");

        assertThat(result).isInstanceOf(DataSource.class);
        assertThat(result).isInstanceOf(SqlTracedDataSource.class);
        assertThat(result).isInstanceOf(VendorDataSource.class);
        assertThat(recorder.dataSourceNames()).contains("workerDataSource");
    }

    @Test
    void leavesBeanUnwrappedWhenTracingDisabled() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(false, false, false, false, 100, 100, 256, 128, 5);
        SqlTraceDataSourceBeanPostProcessor bpp = new SqlTraceDataSourceBeanPostProcessor(provider(recorder));
        VendorPoolDataSource original = new VendorPoolDataSource();

        Object result = bpp.postProcessAfterInitialization(original, "workerDataSource");

        assertThat(result).isSameAs(original);
    }

    private static SqlTraceRecorder enabledRecorder() {
        return new SqlTraceRecorder(true, true, true, false, 100, 100, 256, 128, 5);
    }

    /**
     * Under {@code spring.datasource.connection-fetch=lazy} this post-processor sees the
     * {@link LazyConnectionDataSourceProxy} Spring Boot already substituted, and the pool inside it is not a bean
     * of its own — so tracing has to be inserted underneath the wrapper rather than around it (issue #924).
     */
    @Test
    void tracesThePoolInsideALazyConnectionProxyAndLeavesTheBeanItself() {
        SqlTraceRecorder recorder = enabledRecorder();
        SqlTraceDataSourceBeanPostProcessor bpp = new SqlTraceDataSourceBeanPostProcessor(provider(recorder));
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(new VendorPoolDataSource());

        Object result = bpp.postProcessAfterInitialization(lazy, "dataSource");

        assertThat(result).isSameAs(lazy);
        assertThat(lazy.getTargetDataSource()).isInstanceOf(SqlTracedDataSource.class);
        assertThat(recorder.dataSourceNames()).contains("dataSource");
    }

    @Test
    void leavesAWrapperAloneWhenItsTargetIsAlreadyTraced() {
        SqlTraceRecorder recorder = enabledRecorder();
        SqlTraceDataSourceBeanPostProcessor bpp = new SqlTraceDataSourceBeanPostProcessor(provider(recorder));
        DataSource traced = (DataSource) bpp.postProcessAfterInitialization(new VendorPoolDataSource(), "dataSource");
        DelegatingDataSource wrapper = new DelegatingDataSource(traced);

        Object result = bpp.postProcessAfterInitialization(wrapper, "delegatingDataSource");

        assertThat(result).isSameAs(wrapper);
        assertThat(wrapper.getTargetDataSource()).isSameAs(traced);
        assertThat(recorder.dataSourceNames()).doesNotContain("delegatingDataSource");
    }

    @Test
    void leavesARoutingDataSourceAndItsTargetsAlone() {
        SqlTraceRecorder recorder = enabledRecorder();
        SqlTraceDataSourceBeanPostProcessor bpp = new SqlTraceDataSourceBeanPostProcessor(provider(recorder));
        DataSource primary = new VendorPoolDataSource();
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return "primary";
            }
        };
        routing.setTargetDataSources(Map.of("primary", primary));
        routing.afterPropertiesSet();

        Object result = bpp.postProcessAfterInitialization(routing, "routingDataSource");

        assertThat(result).isSameAs(routing);
        assertThat(routing.getResolvedDataSources().get("primary")).isSameAs(primary);
        assertThat(recorder.dataSourceNames()).doesNotContain("routingDataSource");
    }

    @Test
    void leavesAWrapperUntouchedWhenTracingDisabled() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(false, false, false, false, 100, 100, 256, 128, 5);
        SqlTraceDataSourceBeanPostProcessor bpp = new SqlTraceDataSourceBeanPostProcessor(provider(recorder));
        DataSource pool = new VendorPoolDataSource();
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(pool);

        Object result = bpp.postProcessAfterInitialization(lazy, "dataSource");

        assertThat(result).isSameAs(lazy);
        assertThat(lazy.getTargetDataSource()).isSameAs(pool);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfAvailable(java.util.function.Supplier<T> defaultSupplier) {
                return value != null ? value : defaultSupplier.get();
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    /** Stands in for a vendor-specific {@code DataSource} interface, such as Oracle UCP's {@code PoolDataSource}. */
    public interface VendorDataSource extends DataSource {}

    /** Stands in for a concrete vendor {@code DataSource} bean implementing a vendor-specific interface. */
    private static final class VendorPoolDataSource implements VendorDataSource {

        @Override
        public Connection getConnection() {
            return null;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return null;
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
