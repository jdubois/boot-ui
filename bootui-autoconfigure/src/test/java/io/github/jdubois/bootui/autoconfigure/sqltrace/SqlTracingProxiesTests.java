package io.github.jdubois.bootui.autoconfigure.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.CapturedStatement;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.StatementType;
import java.io.Closeable;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Verifies the hand-written JDBC tracing proxy intercepts the right calls while
 * delegating everything else. JDBC objects are Mockito mocks so the proxy can be
 * exercised without a live database.
 */
class SqlTracingProxiesTests {

    private SqlTraceRecorder recorder() {
        return new SqlTraceRecorder(true, true, true, 100, 100, 2000, 200, 5);
    }

    @Test
    void capturesPreparedQueryWithParametersThreadAndConnectionId() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        String sql = "select * from account where id = ?";
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(sql)).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        try (Connection c = traced.getConnection()) {
            PreparedStatement p = c.prepareStatement(sql);
            p.setLong(1, 42L);
            assertThat(p.executeQuery()).isSameAs(rs);
        }

        assertThat(recorder.recent()).hasSize(1);
        CapturedStatement entry = recorder.recent().get(0);
        assertThat(entry.sql()).isEqualTo(sql);
        assertThat(entry.statementType()).isEqualTo(StatementType.PREPARED);
        assertThat(entry.category()).isEqualTo(Category.SELECT);
        assertThat(entry.success()).isTrue();
        assertThat(entry.connectionId()).startsWith("conn-");
        assertThat(entry.thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(entry.parameters()).containsExactly("42");
    }

    @Test
    void capturesUpdateCountAndCategory() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("update account set name = ?")).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(3);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        PreparedStatement p = traced.getConnection().prepareStatement("update account set name = ?");
        p.setString(1, "alice");
        assertThat(p.executeUpdate()).isEqualTo(3);

        CapturedStatement entry = recorder.recent().get(0);
        assertThat(entry.category()).isEqualTo(Category.UPDATE);
        assertThat(entry.affectedRows()).isEqualTo(3L);
        assertThat(entry.parameters()).containsExactly("'alice'");
    }

    @Test
    void capturesPlainStatementAndClassifiesByKeyword() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.execute("/* hint */ SELECT 1")).thenReturn(true);
        when(stmt.executeQuery("SELECT 1")).thenReturn(rs);
        when(stmt.executeUpdate("delete from account")).thenReturn(2);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        Statement s = traced.getConnection().createStatement();
        s.executeQuery("SELECT 1");
        s.execute("/* hint */ SELECT 1");
        s.executeUpdate("delete from account");

        assertThat(recorder.recent())
                .extracting(CapturedStatement::statementType)
                .containsOnly(StatementType.STATEMENT);
        assertThat(recorder.recent())
                .extracting(CapturedStatement::category)
                .containsExactly(Category.DELETE, Category.SELECT, Category.SELECT);
    }

    @Test
    void capturesBatchSizeCategoryAndSummedRows() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("insert into account(name) values (?)")).thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[] {1, 1, 1});

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        PreparedStatement p = traced.getConnection().prepareStatement("insert into account(name) values (?)");
        p.setString(1, "a");
        p.addBatch();
        p.setString(1, "b");
        p.addBatch();
        p.executeBatch();

        CapturedStatement entry = recorder.recent().get(0);
        assertThat(entry.category()).isEqualTo(Category.INSERT);
        assertThat(entry.batchSize()).isEqualTo(2);
        assertThat(entry.affectedRows()).isEqualTo(3L);
    }

    @Test
    void recordsFailuresAndRethrows() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("update account set name = ?")).thenReturn(ps);
        when(ps.executeUpdate()).thenThrow(new SQLException("duplicate key"));

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        PreparedStatement p = traced.getConnection().prepareStatement("update account set name = ?");

        assertThatThrownBy(p::executeUpdate).isInstanceOf(SQLException.class).hasMessage("duplicate key");
        CapturedStatement entry = recorder.recent().get(0);
        assertThat(entry.success()).isFalse();
        assertThat(entry.errorMessage()).isEqualTo("duplicate key");
    }

    @Test
    void delegatesUnwrapToTargetAndAvoidsDoubleWrapping() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        when(ds.unwrap(DataSource.class)).thenReturn(ds);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        assertThat(traced).isInstanceOf(SqlTracedDataSource.class);
        assertThat(traced.unwrap(DataSource.class)).isSameAs(ds);
        // Wrapping an already-traced data source returns it unchanged.
        assertThat(SqlTracingProxies.wrap(traced, recorder)).isSameAs(traced);
    }

    @Test
    void usesFixedRegistrableInterfaceSetsForEveryProxy() throws Exception {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        CallableStatement cs = mock(CallableStatement.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("select 1")).thenReturn(ps);
        when(conn.prepareCall("{call p()}")).thenReturn(cs);
        when(conn.createStatement()).thenReturn(stmt);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        Connection c = traced.getConnection();

        // The proxy interface sets must match SqlTraceRuntimeHints exactly (order included), otherwise the
        // proxies created at runtime would not match the JDK proxies registered for the native image.
        assertThat(traced.getClass().getInterfaces()).containsExactly(SqlTracingProxies.DATA_SOURCE_INTERFACES);
        assertThat(c.getClass().getInterfaces()).containsExactly(SqlTracingProxies.CONNECTION_INTERFACES);
        assertThat(c.prepareStatement("select 1").getClass().getInterfaces())
                .containsExactly(SqlTracingProxies.PREPARED_STATEMENT_INTERFACES);
        assertThat(c.prepareCall("{call p()}").getClass().getInterfaces())
                .containsExactly(SqlTracingProxies.CALLABLE_STATEMENT_INTERFACES);
        assertThat(c.createStatement().getClass().getInterfaces())
                .containsExactly(SqlTracingProxies.STATEMENT_INTERFACES);
    }

    @Test
    void closeIsNoOpWhenTargetDataSourceIsNotCloseable() {
        SqlTraceRecorder recorder = recorder();
        DataSource ds = mock(DataSource.class);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);

        // The proxy advertises AutoCloseable for Spring's destroy-method inference; closing it must not fail
        // even though the underlying DataSource mock is not AutoCloseable.
        assertThat(traced).isInstanceOf(AutoCloseable.class);
        assertThatNoCloseFailure((AutoCloseable) traced);
    }

    @Test
    void closeIsDelegatedWhenTargetDataSourceIsCloseable() throws Exception {
        SqlTraceRecorder recorder = recorder();
        CloseableDataSource ds = mock(CloseableDataSource.class);

        DataSource traced = SqlTracingProxies.wrap(ds, recorder);
        ((AutoCloseable) traced).close();

        verify(ds).close();
    }

    @Test
    void wrapsDataSourceWhoseClassLoaderCannotSeeBootUiMarker() throws Exception {
        SqlTraceRecorder recorder = recorder();
        // Reproduces Spring Boot DevTools: the data source class is loaded by a parent loader (its
        // driver/pool jar) that cannot see SqlTracedDataSource, which lives in the child RestartClassLoader
        // alongside BootUI's classes. Building the proxy with the data source's own loader used to fail with
        // "SqlTracedDataSource ... is not visible from class loader" because that interface was invisible.
        DataSource isolated = newDataSourceWithoutBootUiVisibility();
        assertThat(loaderSees(isolated.getClass().getClassLoader(), SqlTracedDataSource.class.getName()))
                .isFalse();

        DataSource traced = SqlTracingProxies.wrap(isolated, recorder);

        assertThat(traced).isInstanceOf(SqlTracedDataSource.class);
        assertThat(traced.getConnection()).isNull();
    }

    private static DataSource newDataSourceWithoutBootUiVisibility() throws Exception {
        ClassLoader isolated = new BootUiBlindClassLoader();
        Class<?> type = Class.forName(IsolatedTestDataSource.class.getName(), true, isolated);
        assertThat(type.getClassLoader()).isSameAs(isolated);
        return (DataSource) type.getDeclaredConstructor().newInstance();
    }

    @Test
    void unwrapsAndRewrapsAForeignClassLoaderTracedProxy() throws Exception {
        SqlTraceRecorder recorder = recorder();
        // Reproduces a DataSource preserved across a Spring Boot DevTools restart (e.g. via @RestartScope):
        // it is already a BootUI tracing proxy, but built by the *previous* RestartClassLoader, so its
        // SqlTracedDataSource marker is a different Class than this loader's and instanceof misses it.
        DataSource realPool = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(realPool.getConnection()).thenReturn(conn);
        when(realPool.unwrap(DataSource.class)).thenReturn(realPool);
        when(conn.createStatement()).thenReturn(st);
        when(st.execute("select 1")).thenReturn(true);

        DataSource foreign = newForeignTracedProxy(realPool);
        assertThat(foreign).isNotInstanceOf(SqlTracedDataSource.class);

        DataSource traced = SqlTracingProxies.wrap(foreign, recorder);

        // Re-wrapped with THIS loader's marker (so it is recognised next time), over the real pool rather
        // than nested over the stale foreign proxy — which would double-count and pin the old class loader.
        assertThat(traced).isInstanceOf(SqlTracedDataSource.class);
        assertThat(proxyTarget(traced)).isSameAs(realPool);

        // And it still traces: a statement executed through it is recorded exactly once.
        try (Connection c = traced.getConnection()) {
            c.createStatement().execute("select 1");
        }
        assertThat(recorder.recent()).hasSize(1);
    }

    private static DataSource newForeignTracedProxy(DataSource realTarget) throws Exception {
        ForeignMarkerClassLoader loader = new ForeignMarkerClassLoader();
        Class<?> foreignMarker = Class.forName(SqlTracedDataSource.class.getName(), true, loader);
        assertThat(foreignMarker).isNotSameAs(SqlTracedDataSource.class);
        Class<?>[] interfaces = {DataSource.class, foreignMarker};
        return (DataSource) Proxy.newProxyInstance(loader, interfaces, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    case "toString" -> "ForeignTracedProxy";
                    default -> null;
                };
            }
            return method.invoke(realTarget, args);
        });
    }

    private static Object proxyTarget(Object proxy) throws Exception {
        Object handler = Proxy.getInvocationHandler(proxy);
        for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("target");
                field.setAccessible(true);
                return field.get(handler);
            } catch (NoSuchFieldException ignored) {
                // Walk up to the DelegatingHandler superclass that declares the field.
            }
        }
        throw new NoSuchFieldException("target");
    }

    private static boolean loaderSees(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static void assertThatNoCloseFailure(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ex) {
            throw new AssertionError("close() on a non-closeable traced DataSource must be a no-op", ex);
        }
    }

    /** A {@link DataSource} that is also {@link Closeable}, like Hikari's pool, for the delegation test. */
    private interface CloseableDataSource extends DataSource, Closeable {}

    /**
     * Loads {@link IsolatedTestDataSource} itself (defining it from the test classpath bytes) while
     * delegating only to the JDK platform loader, so the resulting data source class cannot see any BootUI
     * type. This mirrors how DevTools keeps third-party jars in the base loader, separate from BootUI's
     * classes in the child {@code RestartClassLoader}.
     */
    private static final class BootUiBlindClassLoader extends ClassLoader {

        private final ClassLoader source = SqlTracingProxiesTests.class.getClassLoader();

        BootUiBlindClassLoader() {
            super(ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(IsolatedTestDataSource.class.getName())) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = defineFromSource(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        private Class<?> defineFromSource(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (java.io.InputStream in = source.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (java.io.IOException ex) {
                throw new ClassNotFoundException(name, ex);
            }
        }
    }

    /**
     * Defines its own copy of {@link SqlTracedDataSource} from the test classpath bytes while delegating
     * everything else (including {@link DataSource}) to the test class loader. The locally-defined marker is
     * a distinct {@link Class} with the same name, so a proxy built against it mimics a tracing proxy left
     * behind by a previous DevTools {@code RestartClassLoader}.
     */
    private static final class ForeignMarkerClassLoader extends ClassLoader {

        private final ClassLoader source = SqlTracingProxiesTests.class.getClassLoader();

        ForeignMarkerClassLoader() {
            super(SqlTracingProxiesTests.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(SqlTracedDataSource.class.getName())) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = defineFromSource(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        private Class<?> defineFromSource(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (java.io.InputStream in = source.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (java.io.IOException ex) {
                throw new ClassNotFoundException(name, ex);
            }
        }
    }
}
