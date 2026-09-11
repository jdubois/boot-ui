package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.spi.InvocationContextProvider;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * Hand-written JDBC tracing built only on {@link java.lang.reflect.Proxy}.
 *
 * <p>This is the from-scratch replacement for a third-party database-proxy
 * library. {@link #wrap(DataSource, SqlTraceRecorder)} returns a dynamic proxy
 * for the {@code DataSource}; that proxy wraps every {@link Connection} it hands
 * out, and each connection wraps the {@link Statement}, {@link PreparedStatement},
 * and {@link CallableStatement} objects it creates. Only the {@code execute*},
 * {@code addBatch}, and parameter {@code set*} methods are intercepted; every
 * other call is delegated unchanged, which keeps the surface small while remaining
 * transparent to callers (including {@code unwrap}, so pool discovery still finds
 * the real pool).</p>
 *
 * <p><b>GraalVM:</b> each proxy is created over a fixed set of standard JDBC API
 * interfaces (rather than every interface of the concrete driver/pool class) so
 * the proxy classes are known at build time and can be registered as native-image
 * proxy metadata by {@code SqlTraceRuntimeHints} (Spring adapter). The trade-off is that callers who
 * cast a connection/statement directly to a driver-specific type must instead go
 * through {@code unwrap(...)}, which the proxy delegates to the real object.</p>
 */
public final class SqlTracingProxies {

    private static final AtomicLong CONNECTION_IDS = new AtomicLong();

    private static final Set<String> EXECUTE_METHODS =
            Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate");
    private static final Set<String> BATCH_EXECUTE_METHODS = Set.of("executeBatch", "executeLargeBatch");

    static final int MAX_BATCH_PREVIEW_ITEMS = 5;
    static final int MAX_BATCH_PREVIEW_PARAMETERS = 5;
    static final int MAX_BATCH_SQL_PREVIEW_CHARS = 256;
    static final int MAX_BATCH_PARAMETER_PREVIEW_CHARS = 128;

    // Fixed proxy interface sets. These must stay in sync with SqlTraceRuntimeHints, which registers the
    // same JDK proxies for native images; the ordering is significant because it identifies the proxy class.
    static final Class<?>[] DATA_SOURCE_INTERFACES = {DataSource.class, AutoCloseable.class, SqlTracedDataSource.class};
    static final Class<?>[] CONNECTION_INTERFACES = {Connection.class};
    static final Class<?>[] STATEMENT_INTERFACES = {Statement.class};
    static final Class<?>[] PREPARED_STATEMENT_INTERFACES = {PreparedStatement.class};
    static final Class<?>[] CALLABLE_STATEMENT_INTERFACES = {CallableStatement.class};

    private SqlTracingProxies() {}

    /** The connection/statement proxy interface sets, exposed so adapters can register native-image proxy hints. */
    public static Class<?>[] connectionInterfaces() {
        return CONNECTION_INTERFACES.clone();
    }

    public static Class<?>[] statementInterfaces() {
        return STATEMENT_INTERFACES.clone();
    }

    public static Class<?>[] preparedStatementInterfaces() {
        return PREPARED_STATEMENT_INTERFACES.clone();
    }

    public static Class<?>[] callableStatementInterfaces() {
        return CALLABLE_STATEMENT_INTERFACES.clone();
    }

    /**
     * The data-source proxy interface set for a given list of extra adapter interfaces (the Spring adapter
     * passes none; the Quarkus adapter passes {@code AgroalDataSource} so the proxy still satisfies
     * concrete-type injection). Adapters register the returned set as a native-image JDK proxy hint.
     */
    public static Class<?>[] dataSourceInterfaces(Class<?>... extraInterfaces) {
        if (extraInterfaces == null || extraInterfaces.length == 0) {
            return DATA_SOURCE_INTERFACES.clone();
        }
        Class<?>[] combined = new Class<?>[DATA_SOURCE_INTERFACES.length + extraInterfaces.length];
        System.arraycopy(DATA_SOURCE_INTERFACES, 0, combined, 0, DATA_SOURCE_INTERFACES.length);
        System.arraycopy(extraInterfaces, 0, combined, DATA_SOURCE_INTERFACES.length, extraInterfaces.length);
        return combined;
    }

    /** Wraps a data source so all SQL flowing through it is recorded. */
    public static DataSource wrap(DataSource dataSource, SqlTraceRecorder recorder) {
        return wrap(dataSource, recorder, DATA_SOURCE_INTERFACES);
    }

    /**
     * Wraps a data source advertising additional adapter interfaces (in addition to the standard
     * {@link #DATA_SOURCE_INTERFACES}), so concrete-type injectors — such as Quarkus's
     * {@code AgroalDataSource} — still resolve the traced proxy. {@code unwrap} is delegated to the
     * target so connection-pool discovery and metrics still reach the real pool.
     */
    public static DataSource wrap(DataSource dataSource, SqlTraceRecorder recorder, Class<?>... interfaces) {
        return wrapNamed(dataSource, recorder, null, interfaces);
    }

    /** Datasource identity is its adapter bean name, never a JDBC URL. */
    public static DataSource wrapNamed(
            DataSource dataSource, SqlTraceRecorder recorder, String dataSourceName, Class<?>... interfaces) {
        if (dataSource instanceof SqlTracedDataSource) {
            return dataSource;
        }
        DataSource target = unwrapForeignTracedProxy(dataSource);
        return (DataSource) Proxy.newProxyInstance(
                dataSourceProxyClassLoader(target),
                interfaces == null || interfaces.length == 0 ? DATA_SOURCE_INTERFACES : interfaces,
                new DataSourceHandler(target, recorder, dataSourceName));
    }

    /**
     * Returns the real pool behind a BootUI tracing proxy that was built by a <em>different</em> class
     * loader, or the argument unchanged when it is not such a proxy.
     *
     * <p>Under Spring Boot DevTools a data source preserved across a restart (for example a
     * {@code @RestartScope} pool kept so an in-memory database keeps its rows) can still be a tracing
     * proxy created by the previous {@code RestartClassLoader}. Its {@link SqlTracedDataSource} marker is
     * a different {@link Class} than this loader's, so the {@code instanceof} guard in {@link #wrap} misses
     * it. Wrapping it again would nest a second proxy over the stale one — double-counting every statement
     * and pinning the old class loader in memory — so we unwrap back to the genuine pool and re-wrap that
     * with the current recorder instead. Detection is by interface <em>name</em> so it is class-loader
     * agnostic; unwrapping uses the JDBC {@code unwrap} contract the proxy already delegates to its target,
     * and falls back to the proxy unchanged if that throws.</p>
     */
    private static DataSource unwrapForeignTracedProxy(DataSource dataSource) {
        if (!isForeignTracedProxy(dataSource)) {
            return dataSource;
        }
        try {
            DataSource unwrapped = dataSource.unwrap(DataSource.class);
            return unwrapped != null ? unwrapped : dataSource;
        } catch (SQLException | RuntimeException ex) {
            return dataSource;
        }
    }

    private static boolean isForeignTracedProxy(DataSource dataSource) {
        if (dataSource instanceof SqlTracedDataSource || !Proxy.isProxyClass(dataSource.getClass())) {
            return false;
        }
        for (Class<?> iface : dataSource.getClass().getInterfaces()) {
            if (SqlTracedDataSource.class.getName().equals(iface.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Picks a class loader able to see every interface in {@link #DATA_SOURCE_INTERFACES}, including
     * BootUI's own {@link SqlTracedDataSource} marker. Under Spring Boot DevTools the data source is
     * loaded by the base class loader (its driver/pool lives in a jar) while BootUI's classes live in the
     * child {@code RestartClassLoader}, so the data source's own loader cannot see
     * {@code SqlTracedDataSource} and {@link Proxy#newProxyInstance} fails. BootUI's loader is always a
     * descendant of (or equal to) the data source's loader and can still see the standard JDBC interfaces
     * (they come from the JDK), so it is a valid loader for all three proxy interfaces.
     */
    private static ClassLoader dataSourceProxyClassLoader(DataSource dataSource) {
        ClassLoader bootUiLoader = SqlTracingProxies.class.getClassLoader();
        if (bootUiLoader != null) {
            return bootUiLoader;
        }
        return classLoader(dataSource);
    }

    private static ClassLoader classLoader(Object target) {
        ClassLoader loader = target.getClass().getClassLoader();
        return loader != null ? loader : SqlTracingProxies.class.getClassLoader();
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

        private final String dataSourceName;

        DataSourceHandler(Object target, SqlTraceRecorder recorder, String dataSourceName) {
            super(target, recorder);
            this.dataSourceName = dataSourceName;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }
            // The proxy advertises AutoCloseable so Spring still infers a destroy method and closes the
            // pool; if the underlying data source is not closeable, swallow close() instead of failing.
            if ("close".equals(method.getName())
                    && method.getParameterCount() == 0
                    && !(target instanceof AutoCloseable)) {
                return null;
            }
            Object result = invokeTarget(method, args);
            if ("getConnection".equals(method.getName()) && result instanceof Connection connection) {
                return wrapConnection(connection, recorder, dataSourceName);
            }
            return result;
        }
    }

    private static Connection wrapConnection(Connection connection, SqlTraceRecorder recorder, String dataSourceName) {
        String connectionId = "conn-" + CONNECTION_IDS.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(
                classLoader(connection),
                CONNECTION_INTERFACES,
                new ConnectionHandler(connection, recorder, connectionId, dataSourceName));
    }

    /** Wraps the statements a connection creates, remembering prepared/callable SQL. */
    private static final class ConnectionHandler extends DelegatingHandler {

        private final String connectionId;
        private final String dataSourceName;

        ConnectionHandler(Object target, SqlTraceRecorder recorder, String connectionId, String dataSourceName) {
            super(target, recorder);
            this.connectionId = connectionId;
            this.dataSourceName = dataSourceName;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }
            Object result = invokeTarget(method, args);
            String name = method.getName();
            if (result instanceof Statement statement) {
                String sql = (args != null && args.length > 0 && args[0] instanceof String s) ? s : null;
                StatementType type =
                        switch (name) {
                            case "prepareStatement" -> StatementType.PREPARED;
                            case "prepareCall" -> StatementType.CALLABLE;
                            default -> StatementType.STATEMENT;
                        };
                return wrapStatement(statement, recorder, connectionId, type, sql, dataSourceName);
            }
            return result;
        }
    }

    private static Statement wrapStatement(
            Statement statement,
            SqlTraceRecorder recorder,
            String connectionId,
            StatementType type,
            String preparedSql,
            String dataSourceName) {
        Class<?>[] interfaces =
                switch (type) {
                    case PREPARED -> PREPARED_STATEMENT_INTERFACES;
                    case CALLABLE -> CALLABLE_STATEMENT_INTERFACES;
                    case STATEMENT -> STATEMENT_INTERFACES;
                };
        return (Statement) Proxy.newProxyInstance(
                classLoader(statement),
                interfaces,
                new StatementHandler(statement, recorder, connectionId, type, preparedSql, dataSourceName));
    }

    /** Times {@code execute*}/{@code executeBatch}, captures bound parameters, and records the outcome. */
    private static final class StatementHandler extends DelegatingHandler {

        private final String connectionId;
        private final StatementType statementType;
        private final String preparedSql;
        private final String dataSourceName;

        private final TreeMap<Integer, String> parameters = new TreeMap<>();
        private final List<String> batchParameterPreviews = new ArrayList<>(MAX_BATCH_PREVIEW_ITEMS);
        private final List<String> batchSqlPreviews = new ArrayList<>(MAX_BATCH_PREVIEW_ITEMS);
        private int batchSize;

        StatementHandler(
                Object target,
                SqlTraceRecorder recorder,
                String connectionId,
                StatementType statementType,
                String preparedSql,
                String dataSourceName) {
            super(target, recorder);
            this.connectionId = connectionId;
            this.statementType = statementType;
            this.preparedSql = preparedSql;
            this.dataSourceName = dataSourceName;
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
                clearBatchState();
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
                batchSize++;
                if (recorder.isCaptureParameters() && batchParameterPreviews.size() < MAX_BATCH_PREVIEW_ITEMS) {
                    batchParameterPreviews.add(parameterSetPreview(batchSize));
                }
            } else if (args != null && args.length > 0 && args[0] instanceof String sql) {
                batchSize++;
                if (batchSqlPreviews.size() < MAX_BATCH_PREVIEW_ITEMS) {
                    batchSqlPreviews.add(truncatePreview(sql, MAX_BATCH_SQL_PREVIEW_CHARS));
                }
            }
        }

        private Object timeExecution(String name, Method method, Object[] args) throws Throwable {
            String sql = preparedSql != null
                    ? preparedSql
                    : (args != null && args.length > 0 && args[0] instanceof String s ? s : null);
            Category category = classify(name, sql);
            InvocationContextProvider.Context context = recorder.captureContext();
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
                        category,
                        sql,
                        preparedSql != null ? orderedParameters() : List.of(),
                        millis(start),
                        success,
                        error,
                        affected,
                        0,
                        connectionId,
                        Thread.currentThread().getName(),
                        context,
                        dataSourceName);
            }
        }

        private Object timeBatch(Method method, Object[] args) throws Throwable {
            String sql = preparedSql != null ? preparedSql : batchSqlPreview();
            Category category = classify(null, preparedSql != null ? preparedSql : firstBatchSql());
            InvocationContextProvider.Context context = recorder.captureContext();
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
                        category,
                        sql,
                        preparedSql != null ? batchParameterPreview() : List.of(),
                        millis(start),
                        success,
                        error,
                        affected,
                        batchSize,
                        connectionId,
                        Thread.currentThread().getName(),
                        context,
                        dataSourceName);
                clearBatchState();
            }
        }

        private String firstBatchSql() {
            return batchSqlPreviews.isEmpty() ? null : batchSqlPreviews.get(0);
        }

        private String batchSqlPreview() {
            List<String> preview = new ArrayList<>(batchSqlPreviews);
            int omitted = batchSize - preview.size();
            if (omitted > 0) {
                preview.add("-- … (+" + omitted + " more statements)");
            }
            return String.join(";\n", preview);
        }

        private List<String> batchParameterPreview() {
            if (!recorder.isCaptureParameters()) {
                return List.of();
            }
            List<String> preview = new ArrayList<>(batchParameterPreviews);
            int omitted = batchSize - preview.size();
            if (omitted > 0) {
                preview.add("… (+" + omitted + " more parameter sets)");
            }
            return preview;
        }

        private String parameterSetPreview(int batchNumber) {
            StringBuilder preview =
                    new StringBuilder("batch ").append(batchNumber).append(": [");
            int included = 0;
            for (String parameter : parameters.values()) {
                if (included == MAX_BATCH_PREVIEW_PARAMETERS) {
                    break;
                }
                if (included > 0) {
                    preview.append(", ");
                }
                preview.append(truncatePreview(parameter, MAX_BATCH_PARAMETER_PREVIEW_CHARS));
                included++;
            }
            int omitted = parameters.size() - included;
            if (omitted > 0) {
                if (included > 0) {
                    preview.append(", ");
                }
                preview.append("… (+").append(omitted).append(" more values)");
            }
            return preview.append(']').toString();
        }

        private void clearBatchState() {
            batchSize = 0;
            batchParameterPreviews.clear();
            batchSqlPreviews.clear();
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
                return recorder.truncateParameter("'" + text.replace("'", "''") + "'");
            }
            return recorder.truncateParameter(String.valueOf(value));
        }

        private long millis(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }
    }

    private static String truncatePreview(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…";
    }

    /**
     * Classifies an execution into a {@link Category}. {@code executeQuery} always reads, so an
     * unrecognised keyword still maps to {@code SELECT}; other methods fall back to {@code OTHER}.
     */
    private static Category classify(String methodName, String sql) {
        Category byKeyword = categoryOf(sql);
        if ("executeQuery".equals(methodName) && byKeyword == Category.OTHER) {
            return Category.SELECT;
        }
        return byKeyword;
    }

    /**
     * Derives a {@link Category} from the leading SQL keyword, skipping comments and leading parens.
     *
     * <p>Public so an adapter that captures SQL through a mechanism other than the JDBC proxy (e.g. the
     * Quarkus Hibernate {@code StatementInspector}) classifies statements with the exact same logic the
     * proxy uses, keeping the panel's categories consistent regardless of capture source.</p>
     */
    public static Category categoryOf(String sql) {
        return switch (firstKeyword(sql)) {
            case "SELECT", "WITH", "SHOW", "VALUES", "TABLE" -> Category.SELECT;
            case "INSERT" -> Category.INSERT;
            case "UPDATE", "MERGE", "UPSERT" -> Category.UPDATE;
            case "DELETE" -> Category.DELETE;
            case "CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "RENAME", "GRANT", "REVOKE" -> Category.DDL;
            default -> Category.OTHER;
        };
    }

    private static String firstKeyword(String sql) {
        if (sql == null) {
            return "";
        }
        int i = 0;
        int length = sql.length();
        // Skip leading whitespace, line comments, block comments, and opening parens.
        while (i < length) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                i++;
            } else if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i);
                i = newline < 0 ? length : newline + 1;
            } else if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < length && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
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
