package io.github.bootui.autoconfigure.database;

import io.github.bootui.core.BootUiDtos.SqlRequestDto;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Wraps a JDBC {@link DataSource} and records every SQL statement executed
 * through it into a {@link SqlRecorder}.
 *
 * <p>Wrapping is implemented with JDK dynamic proxies so we don't need any
 * additional dependency (such as p6spy or datasource-proxy) on the classpath
 * of the host application.</p>
 *
 * <p>The wrapper exposes the original {@link DataSource} via
 * {@link DataSource#unwrap(Class)}, so pool MBeans and other framework
 * integrations keep working.</p>
 */
public class RecordingDataSourceWrapper {

    private RecordingDataSourceWrapper() {
    }

    public static DataSource wrap(DataSource delegate, String beanName, SqlRecorder recorder) {
        if (delegate == null || recorder == null) {
            return delegate;
        }
        if (delegate instanceof RecordingDataSource) {
            return delegate;
        }
        return new RecordingDataSourceImpl(delegate, beanName, recorder);
    }

    /**
     * Marker interface allowing detection of an already-wrapped data source
     * (defensive: avoids double-wrapping on context refresh).
     */
    public interface RecordingDataSource extends DataSource {
        DataSource bootUiDelegate();
    }

    private static final class RecordingDataSourceImpl implements RecordingDataSource {
        private final DataSource delegate;
        private final String beanName;
        private final SqlRecorder recorder;

        RecordingDataSourceImpl(DataSource delegate, String beanName, SqlRecorder recorder) {
            this.delegate = delegate;
            this.beanName = beanName;
            this.recorder = recorder;
        }

        @Override
        public DataSource bootUiDelegate() {
            return delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrapConnection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrapConnection(delegate.getConnection(username, password));
        }

        private Connection wrapConnection(Connection raw) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    new ConnectionInvocationHandler(raw, beanName, recorder));
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            try {
                return delegate.getParentLogger();
            } catch (java.sql.SQLFeatureNotSupportedException ex) {
                return java.util.logging.Logger.getLogger("global");
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface == null) {
                return null;
            }
            if (iface.isInstance(this)) {
                return (T) this;
            }
            if (iface.isInstance(delegate)) {
                return (T) delegate;
            }
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            if (iface == null) {
                return false;
            }
            return iface.isInstance(this) || iface.isInstance(delegate) || delegate.isWrapperFor(iface);
        }
    }

    /** Invocation handler for {@link Connection}. */
    private static final class ConnectionInvocationHandler implements InvocationHandler {
        private final Connection delegate;
        private final String beanName;
        private final SqlRecorder recorder;

        ConnectionInvocationHandler(Connection delegate, String beanName, SqlRecorder recorder) {
            this.delegate = delegate;
            this.beanName = beanName;
            this.recorder = recorder;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            try {
                Object result = method.invoke(delegate, args);
                if (result instanceof CallableStatement cs) {
                    String sql = firstStringArg(args);
                    return wrapStatement(cs, CallableStatement.class, sql, "CALLABLE");
                }
                if (result instanceof PreparedStatement ps) {
                    String sql = firstStringArg(args);
                    return wrapStatement(ps, PreparedStatement.class, sql, "PREPARED");
                }
                if (result instanceof Statement st && ("createStatement".equals(name))) {
                    return wrapStatement(st, Statement.class, null, "STATEMENT");
                }
                return result;
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }

        private Object wrapStatement(Statement statement, Class<? extends Statement> iface,
                                     String sql, String statementType) {
            return Proxy.newProxyInstance(
                    iface.getClassLoader(),
                    new Class<?>[]{iface},
                    new StatementInvocationHandler(statement, beanName, recorder, sql, statementType));
        }

        private static String firstStringArg(Object[] args) {
            if (args == null || args.length == 0) {
                return null;
            }
            return args[0] instanceof String s ? s : null;
        }
    }

    /** Invocation handler for {@link Statement}, {@link PreparedStatement}, {@link CallableStatement}. */
    private static final class StatementInvocationHandler implements InvocationHandler {
        private final Statement delegate;
        private final String beanName;
        private final SqlRecorder recorder;
        private final String preparedSql;
        private final String statementType;

        StatementInvocationHandler(Statement delegate, String beanName, SqlRecorder recorder,
                                   String preparedSql, String statementType) {
            this.delegate = delegate;
            this.beanName = beanName;
            this.recorder = recorder;
            this.preparedSql = preparedSql;
            this.statementType = statementType;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            boolean isExecute = isExecuteMethod(name);
            if (!isExecute) {
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getTargetException();
                }
            }
            String sql = (args != null && args.length > 0 && args[0] instanceof String s) ? s : preparedSql;
            long startNanos = System.nanoTime();
            long startEpochMillis = System.currentTimeMillis();
            try {
                Object result = method.invoke(delegate, args);
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000L;
                Integer affected = extractAffected(name, result);
                recorder.record(new SqlRequestDto(
                        startEpochMillis,
                        beanName,
                        sql,
                        statementType,
                        elapsedMicros,
                        true,
                        null,
                        affected));
                return result;
            } catch (InvocationTargetException ex) {
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000L;
                Throwable cause = ex.getTargetException();
                recorder.record(new SqlRequestDto(
                        startEpochMillis,
                        beanName,
                        sql,
                        statementType,
                        elapsedMicros,
                        false,
                        cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                        null));
                throw cause;
            }
        }

        private static boolean isExecuteMethod(String name) {
            return "execute".equals(name)
                    || "executeQuery".equals(name)
                    || "executeUpdate".equals(name)
                    || "executeLargeUpdate".equals(name)
                    || "executeBatch".equals(name)
                    || "executeLargeBatch".equals(name);
        }

        private static Integer extractAffected(String methodName, Object result) {
            if (result instanceof Integer i && ("executeUpdate".equals(methodName))) {
                return i;
            }
            if (result instanceof Long l && "executeLargeUpdate".equals(methodName)) {
                return l > Integer.MAX_VALUE ? Integer.MAX_VALUE : l.intValue();
            }
            return null;
        }
    }
}
