package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

final class PostgresTestDataSources {

    private static final PostgresInsightLimits DEFAULT_LIMITS = new PostgresInsightLimits(
            50, 25, 50, 25, 25, 10, 40, 400, Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(2));

    private PostgresTestDataSources() {}

    static PostgresInsightLimits limits() {
        return DEFAULT_LIMITS;
    }

    static ScriptedDataSource postgres() {
        return new ScriptedDataSource(true, "PostgreSQL", "15.4", 15, 4);
    }

    static ScriptedDataSource postgres(int major) {
        return new ScriptedDataSource(true, "PostgreSQL", major + ".0", major, 0);
    }

    static ScriptedDataSource nonPostgres() {
        return new ScriptedDataSource(false, "H2", "2.2", 2, 2);
    }

    static DataSource failing(String message) {
        return new AbstractTestDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException(message);
            }
        };
    }

    static PostgresReadContext context(ScriptedDataSource dataSource, int major, ExposurePolicy exposure)
            throws SQLException {
        return context(dataSource.getConnection(), major, exposure);
    }

    /** For tests that need to drive the connection themselves, typically to leave auto-commit off. */
    static PostgresReadContext context(Connection connection, int major, ExposurePolicy exposure) {
        return new PostgresReadContext(
                connection,
                io.github.jdubois.bootui.engine.databaseadvisor.DatabaseVersion.of(major, 0, major + ".0"),
                PostgresReadBudget.of(Duration.ofSeconds(15), () -> 0),
                limits(),
                exposure);
    }

    static Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            row.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return row;
    }

    static final class ScriptedDataSource extends AbstractTestDataSource {

        private final boolean postgresDefaults;
        private final String productName;
        private final String productVersion;
        private final int major;
        private final int minor;
        private final Map<QueryKind, List<Map<String, Object>>> rows = new HashMap<>();
        private final Map<QueryKind, SQLException> failures = new HashMap<>();
        private final Map<String, SQLException> pinFailures = new HashMap<>();
        private SQLException rollbackFailure;
        private int connections;
        private final List<String> preparedSql = new ArrayList<>();
        private final List<String> executedSql = new ArrayList<>();

        private ScriptedDataSource(
                boolean postgresDefaults, String productName, String productVersion, int major, int minor) {
            this.postgresDefaults = postgresDefaults;
            this.productName = productName;
            this.productVersion = productVersion;
            this.major = major;
            this.minor = minor;
        }

        int connections() {
            return connections;
        }

        List<String> executedSql() {
            return List.copyOf(executedSql);
        }

        List<String> preparedSql() {
            return List.copyOf(preparedSql);
        }

        @SafeVarargs
        final ScriptedDataSource rows(QueryKind kind, Map<String, Object>... rows) {
            this.rows.put(kind, List.of(rows));
            return this;
        }

        /** Makes any session-pin statement containing {@code fragment} fail and abort the transaction. */
        ScriptedDataSource failPin(String fragment, String message) {
            pinFailures.put(fragment, new SQLException(message));
            return this;
        }

        /**
         * Makes the final rollback fail, modelling a connection that cannot be handed back to the pool in
         * the state it was borrowed in.
         */
        ScriptedDataSource failRollback(String message) {
            rollbackFailure = new SQLException(message);
            return this;
        }

        ScriptedDataSource fail(QueryKind kind, String message) {
            failures.put(kind, new SQLException(message));
            return this;
        }

        @Override
        public Connection getConnection() {
            connections++;
            return connection();
        }

        private Connection connection() {
            AtomicBoolean autoCommit = new AtomicBoolean(true);
            AtomicBoolean readOnly = new AtomicBoolean(false);
            // PostgreSQL aborts the whole transaction on any statement error and rejects every later
            // statement with SQLSTATE 25P02 until it is rolled back, so the fixture models that too:
            // without it, per-query savepoints would look unnecessary.
            AtomicBoolean aborted = new AtomicBoolean(false);
            return Connection.class.cast(Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "getMetaData" -> metaData();
                            case "getAutoCommit" -> autoCommit.get();
                            case "setAutoCommit" -> {
                                autoCommit.set((Boolean) arguments[0]);
                                yield null;
                            }
                            case "isReadOnly" -> readOnly.get();
                            case "setReadOnly" -> {
                                readOnly.set((Boolean) arguments[0]);
                                yield null;
                            }
                            case "createStatement" -> statement(aborted);
                            case "prepareStatement" -> preparedStatement(String.valueOf(arguments[0]), aborted);
                            case "setSavepoint" -> {
                                if (aborted.get()) {
                                    throw new SQLException(
                                            "current transaction is aborted, commands ignored until end of"
                                                    + " transaction block",
                                            "25P02");
                                }
                                yield savepoint();
                            }
                            case "releaseSavepoint" -> null;
                            case "rollback" -> {
                                if (rollbackFailure != null && arguments == null) {
                                    throw rollbackFailure;
                                }
                                aborted.set(false);
                                yield null;
                            }
                            case "close" -> null;
                            case "isClosed" -> false;
                            default -> throw new SQLFeatureNotSupportedException(method.getName());
                        };
                    }));
        }

        private Savepoint savepoint() {
            return Savepoint.class.cast(Proxy.newProxyInstance(
                    Savepoint.class.getClassLoader(), new Class<?>[] {Savepoint.class}, (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "getSavepointName" -> "bootui";
                            case "getSavepointId" -> 1;
                            default -> throw new SQLFeatureNotSupportedException(method.getName());
                        };
                    }));
        }

        private DatabaseMetaData metaData() {
            return DatabaseMetaData.class.cast(Proxy.newProxyInstance(
                    DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[] {DatabaseMetaData.class},
                    (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "getDatabaseProductName" -> productName;
                            case "getDatabaseProductVersion" -> productVersion;
                            case "getDatabaseMajorVersion" -> major;
                            case "getDatabaseMinorVersion" -> minor;
                            case "getURL" -> postgresDefaults ? "jdbc:postgresql://localhost/app" : "jdbc:h2:mem:test";
                            default -> throw new SQLFeatureNotSupportedException(method.getName());
                        };
                    }));
        }

        private Statement statement(AtomicBoolean aborted) {
            return Statement.class.cast(Proxy.newProxyInstance(
                    Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "execute" -> {
                                String sql = String.valueOf(arguments[0]);
                                executedSql.add(sql);
                                if (aborted.get()) {
                                    throw new SQLException(
                                            "current transaction is aborted, commands ignored until end of"
                                                    + " transaction block",
                                            "25P02");
                                }
                                for (Map.Entry<String, SQLException> failure : pinFailures.entrySet()) {
                                    if (sql.contains(failure.getKey())) {
                                        aborted.set(true);
                                        throw failure.getValue();
                                    }
                                }
                                yield true;
                            }
                            case "close" -> null;
                            default -> throw new SQLFeatureNotSupportedException(method.getName());
                        };
                    }));
        }

        private PreparedStatement preparedStatement(String sql, AtomicBoolean aborted) {
            preparedSql.add(sql);
            QueryKind kind = QueryKind.of(sql);
            return PreparedStatement.class.cast(Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "setQueryTimeout", "setMaxRows", "setInt", "close" -> null;
                            case "executeQuery" -> {
                                if (aborted.get()) {
                                    throw new SQLException(
                                            "current transaction is aborted, commands ignored until end of"
                                                    + " transaction block",
                                            "25P02");
                                }
                                if (failures.containsKey(kind)) {
                                    aborted.set(true);
                                    throw failures.get(kind);
                                }
                                yield resultSet(rows.getOrDefault(kind, defaultRows(kind)));
                            }
                            default -> throw new SQLFeatureNotSupportedException(method.getName());
                        };
                    }));
        }

        private List<Map<String, Object>> defaultRows(QueryKind kind) {
            if (!postgresDefaults) {
                return List.of();
            }
            return switch (kind) {
                case ROLE -> List.of(row("role_name", "bootui", "monitoring", true));
                case SETTINGS ->
                    List.of(
                            row("name", "autovacuum", "setting", "on", "unit", null, "source", "default"),
                            row(
                                    "name",
                                    "autovacuum_vacuum_threshold",
                                    "setting",
                                    "50",
                                    "unit",
                                    null,
                                    "source",
                                    "default"),
                            row(
                                    "name",
                                    "autovacuum_vacuum_scale_factor",
                                    "setting",
                                    "0.2",
                                    "unit",
                                    null,
                                    "source",
                                    "default"),
                            row("name", "fsync", "setting", "on", "unit", null, "source", "default"),
                            row("name", "full_page_writes", "setting", "on", "unit", null, "source", "default"),
                            row("name", "track_io_timing", "setting", "on", "unit", null, "source", "default"),
                            row("name", "wal_level", "setting", "replica", "unit", null, "source", "default"));
                case VITALS ->
                    List.of(row(
                            "database_name",
                            "app",
                            "database_size",
                            1024L,
                            "xact_commit",
                            100L,
                            "xact_rollback",
                            0L,
                            "blks_read",
                            1L,
                            "blks_hit",
                            99L,
                            "backends",
                            1,
                            "deadlocks",
                            0L,
                            "temp_files",
                            0L,
                            "temp_bytes",
                            0L,
                            "xid_age",
                            1L,
                            "freeze_max_age",
                            200000000L,
                            "max_connections",
                            100));
                case ACTIVITY ->
                    List.of(row(
                            "active_sessions",
                            1,
                            "idle_in_transaction",
                            0,
                            "blocked_sessions",
                            0,
                            "longest_transaction_seconds",
                            0d));
                case SESSIONS ->
                    List.of(row(
                            "pid",
                            42,
                            "user_name",
                            "app",
                            "application_name",
                            "sample-app",
                            "client_address",
                            "127.0.0.1",
                            "state",
                            "active",
                            "wait_event_type",
                            null,
                            "wait_event",
                            null,
                            "blocked_by",
                            null,
                            "state_seconds",
                            0.2d,
                            "transaction_seconds",
                            0.4d,
                            "query_seconds",
                            0.2d,
                            "query",
                            "select 1"));
                case EXTENSION -> List.of(row("relation", "pg_stat_statements", "exec_naming", 1));
                case STATEMENTS, INDEXES, TABLES, VACUUM, REPLICAS -> List.of();
                case RECOVERY -> List.of(row("in_recovery", false, "has_checkpointer", false));
                case CHECKPOINTS ->
                    List.of(row("checkpoints_timed", 10L, "checkpoints_requested", 0L, "write_time", 0d));
                case SLOTS -> List.of(row("slots", 0L, "inactive_slots", 0L));
            };
        }
    }

    enum QueryKind {
        ROLE,
        SETTINGS,
        VITALS,
        ACTIVITY,
        SESSIONS,
        EXTENSION,
        STATEMENTS,
        INDEXES,
        TABLES,
        VACUUM,
        RECOVERY,
        REPLICAS,
        CHECKPOINTS,
        SLOTS;

        static QueryKind of(String sql) {
            if (sql.contains("current_user")) {
                return ROLE;
            }
            if (sql.contains("current_database() as database_name")) {
                return VITALS;
            }
            if (sql.contains("from pg_settings")) {
                return SETTINGS;
            }
            if (sql.contains("pg_blocking_pids")) {
                return SESSIONS;
            }
            if (sql.contains("from pg_stat_activity")) {
                return ACTIVITY;
            }
            if (sql.contains("pg_extension")) {
                return EXTENSION;
            }
            if (sql.contains("from pg_stat_statements")) {
                return STATEMENTS;
            }
            if (sql.contains("from pg_stat_user_indexes")) {
                return INDEXES;
            }
            if (sql.contains("order by total_size")) {
                return TABLES;
            }
            if (sql.contains("n_dead_tup desc")) {
                return VACUUM;
            }
            if (sql.contains("pg_is_in_recovery")) {
                return RECOVERY;
            }
            if (sql.contains("from pg_stat_replication")) {
                return REPLICAS;
            }
            if (sql.contains("pg_stat_bgwriter") || sql.contains("pg_stat_checkpointer")) {
                return CHECKPOINTS;
            }
            if (sql.contains("from pg_replication_slots")) {
                return SLOTS;
            }
            throw new IllegalArgumentException(sql);
        }
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        AtomicInteger position = new AtomicInteger(-1);
        AtomicBoolean wasNull = new AtomicBoolean();
        return ResultSet.class.cast(Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "next" -> position.incrementAndGet() < rows.size();
                        case "close" -> null;
                        case "wasNull" -> wasNull.get();
                        case "getString" -> {
                            Object value = rows.get(position.get()).get(arguments[0]);
                            wasNull.set(value == null);
                            yield value == null ? null : value.toString();
                        }
                        case "getLong" -> {
                            Object value = rows.get(position.get()).get(arguments[0]);
                            wasNull.set(value == null);
                            yield value == null ? 0L : ((Number) value).longValue();
                        }
                        case "getInt" -> {
                            Object value = rows.get(position.get()).get(arguments[0]);
                            wasNull.set(value == null);
                            yield value == null ? 0 : ((Number) value).intValue();
                        }
                        case "getDouble" -> {
                            Object value = rows.get(position.get()).get(arguments[0]);
                            wasNull.set(value == null);
                            yield value == null ? 0d : ((Number) value).doubleValue();
                        }
                        case "getBoolean" -> {
                            Object value = rows.get(position.get()).get(arguments[0]);
                            wasNull.set(value == null);
                            yield Boolean.TRUE.equals(value);
                        }
                        case "getTimestamp" -> {
                            Object value = rows.get(position.get()).get(arguments[0]);
                            wasNull.set(value == null);
                            if (value == null) {
                                yield null;
                            }
                            if (value instanceof Timestamp timestamp) {
                                yield timestamp;
                            }
                            if (value instanceof Number number) {
                                yield new Timestamp(number.longValue());
                            }
                            throw new SQLException("Unsupported timestamp " + value);
                        }
                        default -> throw new SQLFeatureNotSupportedException(method.getName());
                    };
                }));
    }

    abstract static class AbstractTestDataSource implements DataSource {
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
