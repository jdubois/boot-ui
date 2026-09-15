package io.github.jdubois.bootui.engine.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fixed SELECTs only. SQL cap+1, JDBC backstop, server timeout; never JDBC's auxiliary KILL connection. */
final class MySqlQuery {
    private static final String MATERIALIZATION_BUDGET_REASON =
            "Read budget exhausted while consuming results; completed rows are retained and remaining rows are"
                    + " unknown.";

    private MySqlQuery() {}

    static Rows read(Connection connection, MySqlReadBudget budget, String sql, int cap, Object... arguments)
            throws SQLException {
        int millis = budget.selectMillis();
        if (!sql.startsWith("SELECT ") || !sql.endsWith(" LIMIT ?")) {
            throw new IllegalArgumentException("MySQL collectors require a bounded fixed SELECT.");
        }
        String bounded = "SELECT /*+ MAX_EXECUTION_TIME(" + millis + ") */ " + sql.substring(7);
        try (PreparedStatement statement = connection.prepareStatement(bounded)) {
            // Do not use setQueryTimeout: Connector/J implements it through an extra KILL connection.
            statement.setMaxRows(cap + 1);
            int parameter = 1;
            for (Object argument : arguments) {
                statement.setObject(parameter++, argument);
            }
            statement.setInt(parameter, cap + 1);
            try (ResultSet rows = statement.executeQuery()) {
                return rows(rows, cap, budget);
            }
        }
    }

    static Map<String, String> requiredRow(Connection connection, MySqlReadBudget budget, String sql)
            throws SQLException {
        Rows rows = read(connection, budget, sql, 1);
        if (rows.reason() != null) {
            throw new SQLTimeoutException(rows.reason(), "HYT00");
        }
        if (rows.values().size() != 1 || rows.truncated()) {
            throw new SQLException("Required server metadata did not contain exactly one row.", "BUI04");
        }
        return rows.values().get(0);
    }

    /** Fixed 17-name SHOW fallback; output is intrinsically bounded, and the JDBC network guard applies. */
    static Rows status(Connection connection, MySqlReadBudget budget) throws SQLException {
        budget.selectMillis();
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(18);
            try (ResultSet rows = statement.executeQuery(
                    "SHOW GLOBAL STATUS WHERE Variable_name IN (" + MySqlCollectors.STATUS_NAMES + ")")) {
                return rows(rows, 17, budget);
            }
        }
    }

    private static Rows rows(ResultSet rows, int cap, MySqlReadBudget budget) throws SQLException {
        List<Map<String, String>> result = new ArrayList<>();
        ResultSetMetaData metadata = rows.getMetaData();
        int columns = metadata.getColumnCount();
        while (true) {
            if (budget.exhausted()) {
                return new Rows(result, false, MATERIALIZATION_BUDGET_REASON);
            }
            if (!rows.next()) {
                return new Rows(result, false, null);
            }
            if (result.size() == cap) {
                return new Rows(result, true, budget.exhausted() ? MATERIALIZATION_BUDGET_REASON : null);
            }
            if (budget.exhausted()) {
                return new Rows(result, false, MATERIALIZATION_BUDGET_REASON);
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 1; column <= columns; column++) {
                row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT), rows.getString(column));
            }
            result.add(java.util.Collections.unmodifiableMap(row));
        }
    }

    static String reason(SQLException error) {
        if ("BUI04".equals(error.getSQLState())) {
            return "Required server metadata was not reported; no safety or identity value was inferred.";
        }
        if (error instanceof SQLTimeoutException
                || error.getErrorCode() == 3024
                || error.getErrorCode() == 1205
                || "HYT00".equals(error.getSQLState())) {
            return "Time budget or server SELECT/metadata-lock timeout reached; missing rows are unknown.";
        }
        if (denied(error)) {
            return "The connected account cannot read this source; check table-specific SELECT or optional PROCESS.";
        }
        if (fatal(error)) {
            return "The database connection became unusable; remaining sections were not queried.";
        }
        return "The source could not be read on this server. No raw database error or SQL is exposed.";
    }

    static boolean denied(SQLException error) {
        return error.getErrorCode() == 1142 || error.getErrorCode() == 1227 || error.getErrorCode() == 1044;
    }

    static boolean fatal(SQLException error) {
        return error instanceof SQLRecoverableException
                || (error.getSQLState() != null && error.getSQLState().startsWith("08"))
                || error.getErrorCode() == 2006
                || error.getErrorCode() == 2013;
    }

    record Rows(List<Map<String, String>> values, boolean truncated, String reason) {
        Rows {
            values = List.copyOf(values);
        }
    }
}
