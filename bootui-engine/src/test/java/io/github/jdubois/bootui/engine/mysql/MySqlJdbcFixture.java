package io.github.jdubois.bootui.engine.mysql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

/** A deterministic JDBC protocol fixture, not SQL syntax evidence (that belongs to the live suites). */
final class MySqlJdbcFixture {
    final DataSource source = mock(DataSource.class);
    final Connection connection = mock(Connection.class);
    final List<String> sql = new ArrayList<>();
    final List<PreparedStatement> prepared = new ArrayList<>();
    Function<String, List<Map<String, String>>> results = this::defaults;
    String deniedSource;
    String failedRestore;
    boolean abortFails;
    boolean autoCommit = true;
    int networkTimeout = 12000;
    Runnable acquired = () -> {};
    Runnable beforeQuery = () -> {};
    Consumer<String> duringMaterialization = query -> {};

    MySqlJdbcFixture() throws SQLException {
        when(source.getConnection()).thenAnswer(invocation -> {
            acquired.run();
            return connection;
        });
        when(connection.getAutoCommit()).thenAnswer(invocation -> autoCommit);
        doAnswer(invocation -> {
                    autoCommit = invocation.getArgument(0);
                    return null;
                })
                .when(connection)
                .setAutoCommit(any(Boolean.class));
        when(connection.getNetworkTimeout()).thenAnswer(invocation -> networkTimeout);
        doAnswer(invocation -> {
                    networkTimeout = invocation.getArgument(1);
                    return null;
                })
                .when(connection)
                .setNetworkTimeout(any(), anyInt());
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(connection.createStatement()).thenAnswer(invocation -> {
            Statement statement = mock(Statement.class);
            when(statement.execute(anyString())).thenAnswer(call -> {
                String query = call.getArgument(0);
                sql.add(query);
                if (query.equals(failedRestore)) {
                    throw new SQLException("synthetic secret");
                }
                return false;
            });
            return statement;
        });
        doAnswer(invocation -> {
                    if (abortFails) {
                        throw new SQLException("abort unsupported");
                    }
                    return null;
                })
                .when(connection)
                .abort(any());
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            sql.add(query);
            PreparedStatement statement = mock(PreparedStatement.class);
            prepared.add(statement);
            when(statement.executeQuery()).thenAnswer(call -> {
                beforeQuery.run();
                if (deniedSource != null && query.contains(deniedSource)) {
                    throw new SQLException("unsafe credential", "42000", 1142);
                }
                return rows(results.apply(query), () -> duringMaterialization.accept(query));
            });
            return statement;
        });
    }

    List<Map<String, String>> defaults(String query) {
        if (query.contains(" AS select_timeout")) {
            return List.of(row("select_timeout", "99", "lock_timeout", "88", "transaction_read_only", "0"));
        }
        if (query.contains(" AS enforced")) {
            return List.of(row("enforced", "1"));
        }
        if (query.contains("@@version AS version")) {
            return List.of(row(
                    "version",
                    "8.4.6",
                    "flavor",
                    "MySQL Community Server - GPL",
                    "schema_name",
                    "fixture",
                    "account",
                    "reader@local",
                    "server_id",
                    "server-1",
                    "connection_id",
                    "9",
                    "performance_schema",
                    "1",
                    "lower_case_table_names",
                    "0"));
        }
        if (query.contains("setup_consumers")) {
            return List.of(
                    row("name", "statements_digest", "enabled", "YES"),
                    row("name", "global_instrumentation", "enabled", "YES"),
                    row("name", "thread_instrumentation", "enabled", "YES"));
        }
        if (query.contains("setup_instruments")) {
            return List.of(
                    row("category", "statement", "instruments", "1", "enabled", "1", "timed", "1"),
                    row("category", "table", "instruments", "1", "enabled", "1", "timed", "1"),
                    row("category", "metadata-lock", "instruments", "1", "enabled", "1", "timed", "1"));
        }
        if (query.contains("setup_objects")) {
            return List.of(row("schema_name", "%", "object_name", "%", "enabled", "YES", "timed", "YES"));
        }
        if (query.contains("global_status")) {
            return List.of(row("name", "Uptime", "value", "100"), row("name", "Connections", "value", "10"));
        }
        if (query.contains("innodb_metrics")) {
            return List.of(
                    row("name", "lock_deadlocks", "value", "0", "status", "enabled"),
                    row("name", "trx_rseg_history_len", "value", "0", "status", "enabled"));
        }
        return List.of();
    }

    static Map<String, String> row(String... cells) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int index = 0; index < cells.length; index += 2) {
            row.put(cells[index], cells[index + 1]);
        }
        return row;
    }

    static ResultSet rows(List<Map<String, String>> values) throws SQLException {
        return rows(values, () -> {});
    }

    private static ResultSet rows(List<Map<String, String>> values, Runnable materialized) throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        List<String> columns =
                values.isEmpty() ? List.of() : List.copyOf(values.get(0).keySet());
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(columns.size());
        when(metadata.getColumnLabel(anyInt())).thenAnswer(call -> columns.get((Integer) call.getArgument(0) - 1));
        int[] current = {-1};
        when(rows.next()).thenAnswer(call -> ++current[0] < values.size());
        when(rows.getString(anyInt())).thenAnswer(call -> {
            String value = values.get(current[0]).get(columns.get((Integer) call.getArgument(0) - 1));
            materialized.run();
            return value;
        });
        return rows;
    }
}
