package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final Set<String> RESULT_SET_INVALIDATORS = Set.of(
            "absolute",
            "afterLast",
            "beforeFirst",
            "cancelRowUpdates",
            "deleteRow",
            "first",
            "insertRow",
            "last",
            "moveToCurrentRow",
            "moveToInsertRow",
            "previous",
            "refreshRow",
            "relative",
            "updateRow");

    static final int MAX_BATCH_PREVIEW_ITEMS = 5;
    static final int MAX_BATCH_PREVIEW_PARAMETERS = 5;
    static final int MAX_BATCH_SQL_PREVIEW_CHARS = 256;
    static final int MAX_BATCH_PARAMETER_PREVIEW_CHARS = 128;
    static final int MAX_PENDING_RESULT_SETS_PER_CONNECTION = 64;

    // Fixed proxy interface sets. These must stay in sync with SqlTraceRuntimeHints, which registers the
    // same JDK proxies for native images; the ordering is significant because it identifies the proxy class.
    static final Class<?>[] DATA_SOURCE_INTERFACES = {DataSource.class, AutoCloseable.class, SqlTracedDataSource.class};
    static final Class<?>[] CONNECTION_INTERFACES = {Connection.class};
    static final Class<?>[] STATEMENT_INTERFACES = {Statement.class};
    static final Class<?>[] PREPARED_STATEMENT_INTERFACES = {PreparedStatement.class};
    static final Class<?>[] CALLABLE_STATEMENT_INTERFACES = {CallableStatement.class};
    static final Class<?>[] RESULT_SET_INTERFACES = {ResultSet.class};

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

    public static Class<?>[] resultSetInterfaces() {
        return RESULT_SET_INTERFACES.clone();
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

    /**
     * Wraps a data source under its name, such as its bean name, which the connections it lends report to the runtime
     * journal ({@code docs/PLAN-v2.md} §5.2).
     */
    public static DataSource wrapNamed(
            DataSource dataSource, SqlTraceRecorder recorder, String name, Class<?>... interfaces) {
        if (dataSource instanceof SqlTracedDataSource) {
            return dataSource;
        }
        DataSource target = unwrapForeignTracedProxy(dataSource);
        Class<?>[] advertised = interfaces.length == 0 ? DATA_SOURCE_INTERFACES : interfaces;
        DataSource traced = (DataSource) Proxy.newProxyInstance(
                dataSourceProxyClassLoader(target), advertised, new DataSourceHandler(target, recorder, name));
        recorder.registerCaptureSource(name, io.github.jdubois.bootui.engine.journal.SqlPayload.Provenance.EXECUTION);
        return traced;
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

        private final String name;

        DataSourceHandler(Object target, SqlTraceRecorder recorder, String name) {
            super(target, recorder);
            this.name = name;
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
            long start = System.nanoTime();
            Object result = invokeTarget(method, args);
            if ("getConnection".equals(method.getName()) && result instanceof Connection connection) {
                return wrapConnection(
                        connection, recorder, name, recorder.checkoutConnection(name, System.nanoTime() - start));
            }
            return result;
        }
    }

    private static Connection wrapConnection(
            Connection connection, SqlTraceRecorder recorder, String dataSource, ConnectionCheckout checkout) {
        String connectionId = "conn-" + CONNECTION_IDS.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(
                classLoader(connection),
                CONNECTION_INTERFACES,
                new ConnectionHandler(connection, recorder, connectionId, dataSource, checkout));
    }

    /** Wraps the statements a connection creates, remembering prepared/callable SQL. */
    private static final class ConnectionHandler extends DelegatingHandler {

        private final String connectionId;
        private final String dataSource;
        private final ConnectionCheckout checkout;
        private final Set<PendingSqlResult> pendingResults = Collections.newSetFromMap(new IdentityHashMap<>());

        ConnectionHandler(
                Object target,
                SqlTraceRecorder recorder,
                String connectionId,
                String dataSource,
                ConnectionCheckout checkout) {
            super(target, recorder);
            this.connectionId = connectionId;
            this.dataSource = dataSource;
            this.checkout = checkout;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }
            String name = method.getName();
            if (("close".equals(name) && method.getParameterCount() == 0) || "abort".equals(name)) {
                finishPendingResults();
                try {
                    return invokeTarget(method, args);
                } finally {
                    recorder.releaseConnection(checkout);
                }
            }
            if ("commit".equals(name)
                    || "rollback".equals(name)
                    || ("setAutoCommit".equals(name)
                            && args != null
                            && args.length == 1
                            && Boolean.TRUE.equals(args[0]))) {
                finishPendingResults();
            }
            Object result = invokeTarget(method, args);
            if (result instanceof Statement statement) {
                String sql = (args != null && args.length > 0 && args[0] instanceof String s) ? s : null;
                StatementType type =
                        switch (name) {
                            case "prepareStatement" -> StatementType.PREPARED;
                            case "prepareCall" -> StatementType.CALLABLE;
                            default -> StatementType.STATEMENT;
                        };
                return wrapStatement(statement, recorder, connectionId, dataSource, checkout, type, sql, this);
            }
            return result;
        }

        private synchronized boolean register(PendingSqlResult pending) {
            if (pendingResults.size() >= MAX_PENDING_RESULT_SETS_PER_CONNECTION) {
                return false;
            }
            pendingResults.add(pending);
            return true;
        }

        private synchronized void remove(PendingSqlResult pending) {
            pendingResults.remove(pending);
        }

        private void finishPendingResults() {
            List<PendingSqlResult> pending;
            synchronized (this) {
                pending = new ArrayList<>(pendingResults);
            }
            pending.forEach(PendingSqlResult::finishAtConnectionClose);
        }
    }

    private static Statement wrapStatement(
            Statement statement,
            SqlTraceRecorder recorder,
            String connectionId,
            String dataSource,
            ConnectionCheckout checkout,
            StatementType type,
            String preparedSql,
            ConnectionHandler connection) {
        Class<?>[] interfaces =
                switch (type) {
                    case PREPARED -> PREPARED_STATEMENT_INTERFACES;
                    case CALLABLE -> CALLABLE_STATEMENT_INTERFACES;
                    case STATEMENT -> STATEMENT_INTERFACES;
                };
        StatementHandler handler = new StatementHandler(
                statement, recorder, connectionId, dataSource, checkout, type, preparedSql, connection);
        Statement proxy = (Statement) Proxy.newProxyInstance(classLoader(statement), interfaces, handler);
        handler.attachProxy(proxy);
        return proxy;
    }

    private static final class PendingSqlResult {

        private final SqlTraceRecorder.DeferredSqlEvent event;
        private final StatementHandler statement;
        private final ConnectionHandler connection;
        private final boolean deferUntilStatementClose;
        private final boolean countEligible;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private ResultSet target;
        private ResultSet proxy;
        private long rows;
        private boolean valid = true;
        private boolean exhausted;

        PendingSqlResult(
                SqlTraceRecorder.DeferredSqlEvent event,
                StatementHandler statement,
                ConnectionHandler connection,
                boolean deferUntilStatementClose,
                Category category) {
            this.event = event;
            this.statement = statement;
            this.connection = connection;
            this.deferUntilStatementClose = deferUntilStatementClose;
            this.countEligible = category != Category.DDL;
        }

        synchronized ResultSet wrap(ResultSet resultSet) {
            if (proxy != null && target == resultSet) {
                return proxy;
            }
            if (target != null && target != resultSet) {
                finish(null);
                return resultSet;
            }
            target = resultSet;
            try {
                valid = countEligible && resultSet.getType() == ResultSet.TYPE_FORWARD_ONLY;
            } catch (SQLException | RuntimeException ex) {
                valid = false;
            }
            if (proxy != null) {
                return proxy;
            }
            try {
                proxy = (ResultSet) Proxy.newProxyInstance(
                        classLoader(resultSet),
                        RESULT_SET_INTERFACES,
                        new ResultSetHandler(resultSet, this, statement.statementProxy));
                return proxy;
            } catch (RuntimeException ex) {
                finish(null);
                return resultSet;
            }
        }

        synchronized void advanced(boolean hasRow) {
            if (terminal.get() || !valid) {
                return;
            }
            if (!hasRow) {
                exhausted = true;
                if (!deferUntilStatementClose) {
                    finish(rows);
                }
                return;
            }
            if (rows == Long.MAX_VALUE) {
                finish(null);
                return;
            }
            rows++;
        }

        void invalidate() {
            finish(null);
        }

        synchronized void resultSetClosed() {
            if (terminal.get()) {
                return;
            }
            if (!exhausted || !valid) {
                finish(null);
            }
        }

        synchronized void finishAtStatementClose() {
            finish(valid && exhausted ? rows : null);
        }

        void finishAtConnectionClose() {
            finishAtStatementClose();
        }

        synchronized void finish(Long rowCount) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            event.complete(rowCount);
            connection.remove(this);
            statement.clearPendingResult(this);
        }
    }

    private static final class ResultSetHandler extends DelegatingHandler {

        private final PendingSqlResult pending;
        private final Statement statementProxy;

        ResultSetHandler(Object target, PendingSqlResult pending, Statement statementProxy) {
            super(target, pending.statement.recorder);
            this.pending = pending;
            this.statementProxy = statementProxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }
            String name = method.getName();
            if ("getStatement".equals(name) && method.getParameterCount() == 0) {
                return statementProxy;
            }
            if ("next".equals(name) && method.getParameterCount() == 0) {
                try {
                    Object result = invokeTarget(method, args);
                    pending.advanced(Boolean.TRUE.equals(result));
                    return result;
                } catch (Throwable ex) {
                    pending.invalidate();
                    throw ex;
                }
            }
            if ("close".equals(name) && method.getParameterCount() == 0) {
                try {
                    return invokeTarget(method, args);
                } finally {
                    pending.resultSetClosed();
                }
            }
            if ("unwrap".equals(name) || RESULT_SET_INVALIDATORS.contains(name) || name.startsWith("update")) {
                pending.invalidate();
            }
            return invokeTarget(method, args);
        }
    }

    /** Times {@code execute*}/{@code executeBatch}, captures bound parameters, and records the outcome. */
    private static final class StatementHandler extends DelegatingHandler {

        private final String connectionId;
        private final String dataSource;
        private final ConnectionCheckout checkout;
        private final StatementType statementType;
        private final String preparedSql;
        private final ConnectionHandler connection;

        private final TreeMap<Integer, String> parameters = new TreeMap<>();
        private final List<String> batchParameterPreviews = new ArrayList<>(MAX_BATCH_PREVIEW_ITEMS);
        private final List<String> batchSqlPreviews = new ArrayList<>(MAX_BATCH_PREVIEW_ITEMS);
        private int batchSize;
        private boolean batchHasDdl;
        private volatile PendingSqlResult pendingResult;
        private volatile Statement statementProxy;

        StatementHandler(
                Object target,
                SqlTraceRecorder recorder,
                String connectionId,
                String dataSource,
                ConnectionCheckout checkout,
                StatementType statementType,
                String preparedSql,
                ConnectionHandler connection) {
            super(target, recorder);
            this.connectionId = connectionId;
            this.dataSource = dataSource;
            this.checkout = checkout;
            this.statementType = statementType;
            this.preparedSql = preparedSql;
            this.connection = connection;
        }

        void attachProxy(Statement proxy) {
            statementProxy = proxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return handleObjectMethod(proxy, method, args);
            }
            String name = method.getName();
            if ("close".equals(name) && method.getParameterCount() == 0) {
                finishPendingAtStatementClose();
                return invokeTarget(method, args);
            }
            if ("getResultSet".equals(name) && method.getParameterCount() == 0) {
                try {
                    Object result = invokeTarget(method, args);
                    return result instanceof ResultSet resultSet ? wrapCurrentResult(resultSet) : result;
                } catch (Throwable ex) {
                    finishPendingUnknown();
                    throw ex;
                }
            }
            if ("getMoreResults".equals(name)) {
                finishPendingUnknown();
                return invokeTarget(method, args);
            }
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
                finishPendingAtStatementClose();
                return timeBatch(method, args);
            }
            if (EXECUTE_METHODS.contains(name)) {
                finishPendingAtStatementClose();
                return timeExecution(name, method, args);
            }
            return invokeTarget(method, args);
        }

        private ResultSet wrapCurrentResult(ResultSet resultSet) {
            PendingSqlResult pending = pendingResult;
            if (pending == null) {
                return resultSet;
            }
            return pending.wrap(resultSet);
        }

        private void attachPendingResult(
                SqlTraceRecorder.DeferredSqlEvent event,
                ResultSet resultSet,
                boolean deferUntilStatementClose,
                Category category) {
            if (event == null) {
                return;
            }
            PendingSqlResult pending =
                    new PendingSqlResult(event, this, connection, deferUntilStatementClose, category);
            if (!connection.register(pending)) {
                event.complete(null);
                return;
            }
            pendingResult = pending;
            if (resultSet != null) {
                pending.wrap(resultSet);
            }
        }

        private void clearPendingResult(PendingSqlResult pending) {
            if (pendingResult == pending) {
                pendingResult = null;
            }
        }

        private void finishPendingAtStatementClose() {
            PendingSqlResult pending = pendingResult;
            pendingResult = null;
            if (pending != null) {
                pending.finishAtStatementClose();
            }
        }

        private void finishPendingUnknown() {
            PendingSqlResult pending = pendingResult;
            pendingResult = null;
            if (pending != null) {
                pending.finish(null);
            }
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
                batchHasDdl |= classify(null, sql) == Category.DDL;
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
            long start = System.nanoTime();
            boolean success = true;
            String error = null;
            Long affected = null;
            Long rowCount = null;
            Object result = null;
            boolean deferResultSet = false;
            try {
                result = invokeTarget(method, args);
                if (result instanceof Integer count) {
                    affected = count.longValue();
                    rowCount = updateRowCount(category, count.longValue());
                } else if (result instanceof Long count) {
                    affected = count;
                    rowCount = updateRowCount(category, count);
                } else if ("execute".equals(name) && Boolean.FALSE.equals(result) && recorder.recordsSqlRowCounts()) {
                    rowCount = updateRowCount(category, largeUpdateCount());
                }
                deferResultSet = result instanceof ResultSet || ("execute".equals(name) && Boolean.TRUE.equals(result));
            } catch (Throwable ex) {
                success = false;
                error = ex.getMessage();
                throw ex;
            } finally {
                if (checkout != null) {
                    checkout.statementExecuted();
                }
                SqlTraceRecorder.DeferredSqlEvent deferred = null;
                try {
                    deferred = recorder.recordJdbcNanos(
                            statementType,
                            category,
                            sql,
                            preparedSql != null && recorder.capturesForPanel() ? orderedParameters() : List.of(),
                            nanos(start),
                            success,
                            error,
                            affected,
                            0,
                            connectionId,
                            Thread.currentThread().getName(),
                            dataSource,
                            false,
                            rowCount,
                            deferResultSet);
                } catch (RuntimeException ex) {
                    // Capture failures never change the application's JDBC result.
                }
                if (deferResultSet && deferred != null) {
                    try {
                        attachPendingResult(
                                deferred,
                                result instanceof ResultSet resultSet ? resultSet : null,
                                "execute".equals(name),
                                category);
                    } catch (RuntimeException ex) {
                        deferred.complete(null);
                    }
                }
            }
            if (result instanceof ResultSet resultSet) {
                return wrapCurrentResult(resultSet);
            }
            return result;
        }

        private Object timeBatch(Method method, Object[] args) throws Throwable {
            String sql = preparedSql != null ? preparedSql : batchSqlPreview();
            Category category = classify(null, preparedSql != null ? preparedSql : firstBatchSql());
            long start = System.nanoTime();
            boolean success = true;
            String error = null;
            Long affected = null;
            Long rowCount = null;
            try {
                Object result = invokeTarget(method, args);
                affected = sumBatchResult(result);
                rowCount = batchHasDdl ? null : exactBatchResult(result, category);
                return result;
            } catch (Throwable ex) {
                success = false;
                error = ex.getMessage();
                throw ex;
            } finally {
                if (checkout != null) {
                    checkout.statementExecuted();
                }
                try {
                    recorder.recordJdbcNanos(
                            statementType,
                            category,
                            sql,
                            preparedSql != null && recorder.capturesForPanel() ? batchParameterPreview() : List.of(),
                            nanos(start),
                            success,
                            error,
                            affected,
                            batchSize,
                            connectionId,
                            Thread.currentThread().getName(),
                            dataSource,
                            true,
                            rowCount,
                            false);
                } catch (RuntimeException ex) {
                    // Capture failures never change the application's JDBC result.
                }
                clearBatchState();
            }
        }

        private Long largeUpdateCount() {
            try {
                long count = ((Statement) target).getLargeUpdateCount();
                return count < 0 ? null : count;
            } catch (SQLFeatureNotSupportedException | UnsupportedOperationException ex) {
                try {
                    int count = ((Statement) target).getUpdateCount();
                    return count < 0 ? null : (long) count;
                } catch (SQLException | RuntimeException fallbackFailure) {
                    return null;
                }
            } catch (SQLException | RuntimeException ex) {
                return null;
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
            batchHasDdl = false;
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

        /**
         * Elapsed time in nanoseconds. Deliberately not milliseconds: an ordinary primary-key {@code SELECT}
         * against a local database finishes in a few hundred microseconds, so truncating here would record
         * almost every execution as {@code 0} and leave every downstream aggregate with nothing to rank. The panel
         * keeps microseconds, and the runtime journal the nanoseconds.
         */
        private long nanos(long startNanos) {
            return Math.max(0L, System.nanoTime() - startNanos);
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

    private static Long exactBatchResult(Object result, Category category) {
        if (category == Category.DDL) {
            return null;
        }
        long sum = 0;
        try {
            if (result instanceof int[] counts) {
                for (int count : counts) {
                    if (count < 0) {
                        return null;
                    }
                    sum = Math.addExact(sum, count);
                }
                return sum;
            }
            if (result instanceof long[] counts) {
                for (long count : counts) {
                    if (count < 0) {
                        return null;
                    }
                    sum = Math.addExact(sum, count);
                }
                return sum;
            }
        } catch (ArithmeticException ex) {
            return null;
        }
        return null;
    }

    private static Long updateRowCount(Category category, Long count) {
        return category == Category.DDL || count == null || count < 0 ? null : count;
    }
}
