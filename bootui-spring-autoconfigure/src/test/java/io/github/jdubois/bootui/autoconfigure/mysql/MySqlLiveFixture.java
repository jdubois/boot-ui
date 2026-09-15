package io.github.jdubois.bootui.autoconfigure.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.engine.mysql.MySqlInsightService;
import io.github.jdubois.bootui.engine.mysql.MySqlRowLimits;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.testcontainers.mysql.MySQLContainer;

/** Disposable synthetic fixtures only. Production code never imports the connector or the pool. */
final class MySqlLiveFixture {
    static final String IMAGE = "mysql:8.4.6";
    static final String PASSWORD = "synthetic-fixture-only";

    private MySqlLiveFixture() {}

    static MySQLContainer container() {
        return new MySQLContainer(IMAGE)
                .withDatabaseName("bootui_fixture")
                .withUsername("application")
                .withPassword(PASSWORD)
                .withCommand("--performance-schema=ON", "--performance-schema-digests-size=1000");
    }

    static void initialize(MySQLContainer container) throws SQLException, IOException {
        try (Connection connection = admin(container);
                Statement statement = connection.createStatement();
                var resource = MySqlLiveFixture.class.getResourceAsStream("/mysql/fixture.sql")) {
            if (resource == null) {
                throw new IOException("MySQL fixture resource missing");
            }
            for (String sql : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    static Connection admin(MySQLContainer container) throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), "root", container.getPassword());
    }

    static HikariDataSource pool(MySQLContainer container, String user) {
        return pool(container, user, "");
    }

    static HikariDataSource pool(MySQLContainer container, String user, String options) {
        return pool(container, user, options, true);
    }

    static HikariDataSource pool(MySQLContainer container, String user, String options, boolean autoCommit) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl() + (container.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectTimeout=2000&socketTimeout=10000&" + options);
        config.setUsername(user);
        config.setPassword(PASSWORD);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1000);
        config.setAutoCommit(autoCommit);
        return new HikariDataSource(config);
    }

    static MySqlInsightService service(DataSource source) {
        return service(source, MySqlRowLimits.defaults());
    }

    static MySqlInsightService service(DataSource source, MySqlRowLimits limits) {
        return MySqlInsightService.using(
                () -> new DatabaseAdvisorDataSourceDiscovery(List.of(new NamedDataSource("mysql", source)), List.of()),
                null,
                Clock.systemUTC(),
                limits);
    }

    static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    static final class ObservedDataSource extends AbstractDataSource {
        final DataSource delegate;
        final List<String> sql = new ArrayList<>();
        UnaryOperator<String> rewrite = UnaryOperator.identity();
        boolean probeWrite;
        boolean writeRejected;
        boolean failRestore;
        boolean aborted;
        int borrows;
        SqlProbe beforeStatistics;
        SqlProbe beforeShowStatus;

        ObservedDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            borrows++;
            Connection actual = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        if ("abort".equals(method.getName())) {
                            aborted = true;
                        }
                        if ("unwrap".equals(method.getName()) && arguments[0] == Connection.class) {
                            Connection physical = actual.unwrap(Connection.class);
                            return Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (physicalProxy, physicalMethod, physicalArguments) -> {
                                        if ("abort".equals(physicalMethod.getName())) {
                                            aborted = true;
                                        }
                                        return invoke(physical, physicalMethod, physicalArguments);
                                    });
                        }
                        if ("prepareStatement".equals(method.getName()) && arguments[0] instanceof String query) {
                            sql.add(query);
                            if (beforeStatistics != null && query.contains("FROM performance_schema.global_status")) {
                                SqlProbe probe = beforeStatistics;
                                beforeStatistics = null;
                                probe.run(actual);
                            }
                            if (probeWrite && query.contains("@@version AS version")) {
                                try (Statement statement = actual.createStatement()) {
                                    try {
                                        statement.executeUpdate(
                                                "UPDATE sample_orders SET label='must-not-write' WHERE id=1");
                                    } catch (SQLException failure) {
                                        writeRejected = failure.getErrorCode() == 1792;
                                    }
                                }
                            }
                            arguments[0] = rewrite.apply(query);
                        }
                        if ("createStatement".equals(method.getName())) {
                            Statement actualStatement = actual.createStatement();
                            return Proxy.newProxyInstance(
                                    Statement.class.getClassLoader(),
                                    new Class<?>[] {Statement.class},
                                    (statementProxy, statementMethod, statementArguments) -> {
                                        if ("execute".equals(statementMethod.getName())
                                                && statementArguments[0] instanceof String query) {
                                            sql.add(query);
                                            if (failRestore && query.equals("SET SESSION max_execution_time=1234")) {
                                                throw new SQLException("synthetic restoration failure");
                                            }
                                        }
                                        if ("executeQuery".equals(statementMethod.getName())
                                                && statementArguments[0] instanceof String query
                                                && query.startsWith("SHOW GLOBAL STATUS")
                                                && beforeShowStatus != null) {
                                            beforeShowStatus.run(actual);
                                        }
                                        return invoke(actualStatement, statementMethod, statementArguments);
                                    });
                        }
                        return invoke(actual, method, arguments);
                    });
        }

        @FunctionalInterface
        interface SqlProbe {
            void run(Connection connection) throws SQLException;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("Explicit credential override not allowed by this fixture.");
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments)
                throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }
}
