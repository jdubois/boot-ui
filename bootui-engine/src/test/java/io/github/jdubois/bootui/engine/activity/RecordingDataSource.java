package io.github.jdubois.bootui.engine.activity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Wraps a real (H2) {@code DataSource} so {@link JdbcActivityStore}'s dialect handling can be tested without a live
 * MySQL or Oracle server: connections report {@code reportedProductName} from their metadata, every SQL string the
 * store sends is recorded, and the store's page read can be made to fail as a database that rejects its syntax would.
 */
final class RecordingDataSource {

    private static final String PAGE_READ_PREFIX = "SELECT instance_id";

    final List<String> sql = new CopyOnWriteArrayList<>();
    final AtomicInteger productNameCalls = new AtomicInteger();

    private final DataSource delegate;
    private final String reportedProductName;
    private final boolean rejectPageReads;

    RecordingDataSource(DataSource delegate, String reportedProductName, boolean rejectPageReads) {
        this.delegate = delegate;
        this.reportedProductName = reportedProductName;
        this.rejectPageReads = rejectPageReads;
    }

    /** The page reads ({@code SELECT ... ORDER BY ... <row limit>}) the store prepared, in order. */
    List<String> pageReads() {
        return sql.stream()
                .filter(statement -> statement.startsWith(PAGE_READ_PREFIX))
                .toList();
    }

    DataSource dataSource() {
        return proxy(DataSource.class, (proxy, method, args) -> {
            Object result = invoke(delegate, method, args);
            return result instanceof Connection connection ? connection(connection) : result;
        });
    }

    private Connection connection(Connection actual) {
        return proxy(Connection.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getMetaData" -> {
                    return metaData(actual.getMetaData());
                }
                case "prepareStatement" -> {
                    String statement = (String) args[0];
                    sql.add(statement);
                    if (rejectPageReads && statement.startsWith(PAGE_READ_PREFIX)) {
                        throw new SQLException("synthetic syntax error near the row limit", "42000");
                    }
                }
                case "createStatement" -> {
                    return statement((Statement) invoke(actual, method, args));
                }
                default -> {}
            }
            return invoke(actual, method, args);
        });
    }

    private DatabaseMetaData metaData(DatabaseMetaData actual) {
        return proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            if ("getDatabaseProductName".equals(method.getName())) {
                productNameCalls.incrementAndGet();
                return reportedProductName;
            }
            return invoke(actual, method, args);
        });
    }

    private Statement statement(Statement actual) {
        return proxy(Statement.class, (proxy, method, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof String statement) {
                sql.add(statement);
            }
            return invoke(actual, method, args);
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
