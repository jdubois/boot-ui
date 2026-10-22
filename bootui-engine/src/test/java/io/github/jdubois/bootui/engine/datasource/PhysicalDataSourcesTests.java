package io.github.jdubois.bootui.engine.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PhysicalDataSourcesTests {

    @Test
    void aPlainDataSourceIsItsOwnPhysicalIdentity() {
        DataSource pool = plain();

        assertThat(PhysicalDataSources.physical(pool)).isSameAs(pool);
    }

    @Test
    void theSqlTraceProxyIsReducedToThePoolItDelegatesUnwrapTo() {
        DataSource pool = plain();
        DataSource traced = traced(pool);

        assertThat(PhysicalDataSources.physical(traced)).isSameAs(pool);
        assertThat(PhysicalDataSources.physical(traced(traced))).isSameAs(pool);
    }

    @Test
    void aTraceProxyThatCannotUnwrapStaysItsOwnIdentity() {
        DataSource refusing = proxy((proxy, method, args) -> {
            if (method.getName().equals("unwrap")) throw new SQLException("no");
            return null;
        });

        assertThat(PhysicalDataSources.physical(refusing)).isSameAs(refusing);
    }

    @Test
    void theFrameworkStepLooksThroughWrappersAroundTheTraceProxyAndIsBounded() {
        DataSource pool = plain();
        DataSource traced = traced(pool);
        DataSource wrapper = plain();
        UnaryOperator<DataSource> step = candidate -> candidate == wrapper ? traced : null;

        assertThat(PhysicalDataSources.physical(wrapper, step)).isSameAs(pool);

        DataSource first = plain();
        DataSource second = plain();
        UnaryOperator<DataSource> cycle = candidate -> candidate == first ? second : first;
        assertThat(PhysicalDataSources.physical(first, cycle)).isNotNull();
        UnaryOperator<DataSource> failing = candidate -> {
            throw new IllegalStateException("wrapper refused");
        };
        assertThat(PhysicalDataSources.physical(wrapper, failing)).isSameAs(wrapper);
    }

    @Test
    void aTraceProxyCarryingTheMarkerOfAnEarlierClassLoaderIsStillRecognised() throws Exception {
        byte[] marker;
        try (var in = SqlTracedDataSource.class.getResourceAsStream("SqlTracedDataSource.class")) {
            marker = in.readAllBytes();
        }
        ClassLoader earlier = new ClassLoader(PhysicalDataSourcesTests.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(SqlTracedDataSource.class.getName())) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, marker, 0, marker.length);
                }
            }
        };
        Class<?> foreignMarker = earlier.loadClass(SqlTracedDataSource.class.getName());
        assertThat(foreignMarker).isNotEqualTo(SqlTracedDataSource.class);
        DataSource pool = plain();
        DataSource traced = (DataSource) Proxy.newProxyInstance(
                earlier,
                new Class<?>[] {DataSource.class, foreignMarker},
                (proxy, method, args) -> method.getName().equals("unwrap") ? pool : null);

        assertThat(PhysicalDataSources.physical(traced)).isSameAs(pool);
    }

    @Test
    void nullStaysNull() {
        assertThat(PhysicalDataSources.physical(null)).isNull();
    }

    private static DataSource plain() {
        return create(false, (proxy, method, args) -> null);
    }

    private static DataSource traced(DataSource target) {
        return create(true, (proxy, method, args) -> method.getName().equals("unwrap") ? target : null);
    }

    private static DataSource proxy(InvocationHandler handler) {
        return create(true, handler);
    }

    private static DataSource create(boolean marked, InvocationHandler handler) {
        return (DataSource) Proxy.newProxyInstance(
                PhysicalDataSourcesTests.class.getClassLoader(),
                marked
                        ? new Class<?>[] {DataSource.class, SqlTracedDataSource.class}
                        : new Class<?>[] {DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "dataSource";
                    default -> handler.invoke(proxy, method, args);
                });
    }
}
