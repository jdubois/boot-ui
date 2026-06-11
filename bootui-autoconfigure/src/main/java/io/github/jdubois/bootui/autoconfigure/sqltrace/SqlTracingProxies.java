package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.Operation;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder.StatementType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.util.ClassUtils;

/**
 * Hand-written JDBC tracing built only on {@link java.lang.reflect.Proxy}.
 *
 * <p>This is the from-scratch replacement for a third-party database-proxy
 * library. {@link #wrap(DataSource, SqlTraceRecorder)} returns a dynamic proxy
 * for the {@code DataSource}; that proxy wraps every {@link java.sql.Connection}
 * it hands out, and each connection wraps the {@link java.sql.Statement},
 * {@link java.sql.PreparedStatement}, and {@link java.sql.CallableStatement}
 * objects it creates. Only the {@code execute*}, {@code addBatch}, and parameter
 * {@code set*} methods are intercepted; every other call is delegated unchanged,
 * which keeps the surface small while remaining transparent to callers
 * (including {@code unwrap}, so pool discovery still finds the real pool).</p>
 */
final class SqlTracingProxies {

    private static final AtomicLong CONNECTION_IDS = new AtomicLong();

    private static final Set<String> EXECUTE_METHODS =
            Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate");
    private static final Set<String> BATCH_EXECUTE_METHODS = Set.of("executeBatch", "executeLargeBatch");

    private SqlTracingProxies() {}

    /** Wraps a data source so all SQL flowing through it is recorded. */
    static DataSource wrap(DataSource dataSource, SqlTraceRecorder recorder) {
        if (dataSource instanceof SqlTracedDataSource) {
            return dataSource;
        }
        Class<?>[] interfaces = proxyInterfaces(dataSource.getClass(), SqlTracedDataSource.class);
        return (DataSource) Proxy.newProxyInstance(
                classLoader(dataSource), interfaces, new DataSourceHandler(dataSource, recorder));
    }

    private static ClassLoader classLoader(Object target) {
        ClassLoader loader = target.getClass().getClassLoader();
        return loader != null ? loader : SqlTracingProxies.class.getClassLoader();
    }

    private static Class<?>[] proxyInterfaces(Class<?> targetType, Class<?> extra) {
        Set<Class<?>> interfaces = new java.util.LinkedHashSet<>(
                List.of(ClassUtils.getAllInterfacesForClass(targetType, SqlTracingProxies.class.getClassLoader())));
        if (extra != null) {
            interfaces.add(extra);
        }
        return interfaces.toArray(Class<?>[]::new);
    }

    /** Base handler that delegates {@code Object} plumbing and unknown methods to the target. */
    private abstract static class DelegatingHandler implements InvocationHandler {

        final Object target;
        final SqlTraceRecorder recorder;

        DelegatingHandler(Object target, SqlTraceRecorder recorder) {
            this.target = target;
            this.recorder = recorder;
        }

        Object invokeTarget(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() != null ? ex.getCause() : ex;
            }
        }

        Object handleObjectMethod(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args != null ? args[0] : null);
                case "toString" -> "SqlTraceProxy[" + target + "]";
                default -> null;
            };
        }

        boolean isObjectMethod(Method method) {
            return method.getDeclaringClass() == Object.class;
        }
    }

    /** Wraps {@code getConnection} results; delegates everything else (including {@code unwrap}). */
    private static final class DataSourceHandler extends DelegatingHandler {

        DataSourceHandler(Object target, SqlTraceRecorder recorder) {
            super(target, recorder);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }
            Object result = invokeTarget(method, args);
            if ("getConnection".equals(method.getName()) && result instanceof java.sql.Connection connection) {
                return wrapConnection(connection, recorder);
            }
            return result;
        }
    }

    private static java.sql.Connection wrapConnection(java.sql.Connection connection, SqlTraceRecorder recorder) {
        String connectionId = "conn-" + CONNECTION_IDS.incrementAndGet();
        Class<?>[] interfaces = proxyInterfaces(connection.getClass(), null);
        return (java.sql.Connection) Proxy.newProxyInstance(
                classLoader(connection), interfaces, new ConnectionHandler(connection, recorder, connectionId));
    }

    /** Wraps the statements a connection creates, remembering prepared/callable SQL. */
    private static final class ConnectionHandler extends DelegatingHandler {

        private final String connectionId;

        ConnectionHandler(Object target, SqlTraceRecorder recorder, String connectionId) {
            super(target, recorder);
            this.connectionId = connectionId;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }
            Object result = invokeTarget(method, args);
            String name = method.getName();
            if (result instanceof java.sql.Statement statement) {
                String sql = (args != null && args.length > 0 && args[0] instanceof String s) ? s : null;
                StatementType type =
                        switch (name) {
                            case "prepareStatement" -> StatementType.PREPARED;
                            case "prepareCall" -> StatementType.CALLABLE;
                            default -> StatementType.STATEMENT;
                        };
                return wrapStatement(statement, recorder, connectionId, type, sql);
            }
            return result;
        }
    }

    private static java.sql.Statement wrapStatement(
            java.sql.Statement statement,
            SqlTraceRecorder recorder,
            String connectionId,
            StatementType type,
            String preparedSql) {
        Class<?>[] interfaces = proxyInterfaces(statement.getClass(), null);
        return (java.sql.Statement) Proxy.newProxyInstance(
                classLoader(statement),
                interfaces,
                new StatementHandler(statement, recorder, connectionId, type, preparedSql));
    }

    /** Times {@code execute*}/{@code executeBatch}, captures bound parameters, and records the outcome. */
    private static final class StatementHandler extends DelegatingHandler {

        private final String connectionId;
        private final StatementType statementType;
        private final String preparedSql;

        private final TreeMap<Integer, String> parameters = new TreeMap<>();
        private final List<List<String>> batchParameterSets = new ArrayList<>();
        private final List<String> batchSqls = new ArrayList<>();

        StatementHandler(
                Object target,
                SqlTraceRecorder recorder,
                String connectionId,
                StatementType statementType,
                String preparedSql) {
            super(target, recorder);
            this.connectionId = connectionId;
            this.statementType = statementType;
            this.preparedSql = preparedSql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }
            String name = method.getName();
            if (name.startsWith("set") && isParameterSetter(method, args)) {
                captureParameter(name, args);
                return invokeTarget(method, args);
            }
            if ("clearParameters".equals(name)) {
                parameters.clear();
                return invokeTarget(method, args);
            }
            if ("addBatch".equals(name)) {
                rememberBatch(args);
                return invokeTarget(method, args);
            }
            if ("clearBatch".equals(name)) {
                batchParameterSets.clear();
                batchSqls.clear();
                return invokeTarget(method, args);
            }
            if (BATCH_EXECUTE_METHODS.contains(name)) {
                return timeBatch(method, args);
            }
            if (EXECUTE_METHODS.contains(name)) {
                return timeExecution(name, method, args);
            }
            return invokeTarget(method, args);
        }

        private boolean isParameterSetter(Method method, Object[] args) {
            Class<?>[] types = method.getParameterTypes();
            return args != null && args.length >= 1 && types.length >= 1 && types[0] == int.class;
        }

        private void captureParameter(String name, Object[] args) {
            int index = (int) args[0];
            String value;
            if ("setNull".equals(name)) {
                value = "NULL";
            } else if (args.length >= 2) {
                value = formatValue(args[1]);
            } else {
                value = "?";
            }
            parameters.put(index, value);
        }

        private void rememberBatch(Object[] args) {
            if (preparedSql != null) {
                batchParameterSets.add(orderedParameters());
            } else if (args != null && args.length > 0 && args[0] instanceof String sql) {
                batchSqls.add(sql);
            }
        }

        private Object timeExecution(String name, Method method, Object[] args) throws Throwable {
            String sql = preparedSql != null
                    ? preparedSql
                    : (args != null && args.length > 0 && args[0] instanceof String s ? s : null);
            Operation operation = classifyOperation(name, sql);
            long start = System.nanoTime();
            boolean success = true;
            String error = null;
            Long affected = null;
            try {
                Object result = invokeTarget(method, args);
                if (result instanceof Integer count) {
                    affected = count.longValue();
                } else if (result instanceof Long count) {
                    affected = count;
                }
                return result;
            } catch (Throwable ex) {
                success = false;
                error = ex.getMessage();
                throw ex;
            } finally {
                recorder.record(
                        statementType,
                        operation,
                        sql,
                        preparedSql != null ? orderedParameters() : List.of(),
                        millis(start),
                        success,
                        error,
                        affected,
                        0,
                        connectionId);
            }
        }

        private Object timeBatch(Method method, Object[] args) throws Throwable {
            String sql = preparedSql != null ? preparedSql : String.join(";\n", batchSqls);
            int batchSize = preparedSql != null ? batchParameterSets.size() : batchSqls.size();
            long start = System.nanoTime();
            boolean success = true;
            String error = null;
            Long affected = null;
            try {
                Object result = invokeTarget(method, args);
                affected = sumBatchResult(result);
                return result;
            } catch (Throwable ex) {
                success = false;
                error = ex.getMessage();
                throw ex;
            } finally {
                recorder.record(
                        statementType,
                        Operation.BATCH,
                        sql,
                        List.of(),
                        millis(start),
                        success,
                        error,
                        affected,
                        batchSize,
                        connectionId);
                batchParameterSets.clear();
                batchSqls.clear();
            }
        }

        private List<String> orderedParameters() {
            return new ArrayList<>(parameters.values());
        }

        private String formatValue(Object value) {
            if (value == null) {
                return "NULL";
            }
            if (value instanceof byte[] bytes) {
                return "<bytes:" + bytes.length + ">";
            }
            if (value instanceof String text) {
                return recorder.truncateParameter("'" + text + "'");
            }
            return recorder.truncateParameter(String.valueOf(value));
        }

        private long millis(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }
    }

    private static Operation classifyOperation(String methodName, String sql) {
        if ("executeQuery".equals(methodName)) {
            return Operation.QUERY;
        }
        if ("executeUpdate".equals(methodName) || "executeLargeUpdate".equals(methodName)) {
            return Operation.UPDATE;
        }
        // Plain execute(...) is ambiguous, classify by the leading SQL keyword.
        if (sql == null) {
            return Operation.OTHER;
        }
        String keyword = sql.stripLeading().toLowerCase(Locale.ROOT);
        if (keyword.startsWith("select") || keyword.startsWith("with") || keyword.startsWith("show")) {
            return Operation.QUERY;
        }
        if (keyword.startsWith("insert")
                || keyword.startsWith("update")
                || keyword.startsWith("delete")
                || keyword.startsWith("merge")) {
            return Operation.UPDATE;
        }
        return Operation.OTHER;
    }

    private static Long sumBatchResult(Object result) {
        long sum = 0;
        if (result instanceof int[] counts) {
            for (int count : counts) {
                if (count > 0) {
                    sum += count;
                }
            }
            return sum;
        }
        if (result instanceof long[] counts) {
            for (long count : counts) {
                if (count > 0) {
                    sum += count;
                }
            }
            return sum;
        }
        return null;
    }
}
