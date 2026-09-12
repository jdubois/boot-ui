package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one bounded, read-only statistics query and turns it into a {@link PostgresRows}.
 *
 * <p>Every list query is bounded three ways: a trailing {@code limit ?} bound to {@code max + 1} (so
 * exceeding the bound is detected deterministically instead of silently returning a full-looking page),
 * {@code setMaxRows} as a driver-side backstop, and {@code setQueryTimeout} clamped to whatever is left of
 * the read budget. Failures are captured with a redacted reason rather than swallowed, so a section can say
 * "the statistics view refused" instead of reporting a clean result it never earned.</p>
 */
final class PostgresQuery {

    @FunctionalInterface
    interface RowMapper<T> {
        /** Maps the current row, or returns {@code null} to skip it. */
        T map(ResultSet resultSet) throws SQLException;
    }

    private PostgresQuery() {}

    /** Reads a bounded list from a statement whose last placeholder is a trailing {@code limit ?}. */
    static <T> PostgresRows<T> readList(
            PostgresReadContext context, String label, String sql, int max, RowMapper<T> mapper) {
        if (context.budget().exhausted()) {
            return PostgresRows.failed("The read budget ran out before " + label + " could be read.");
        }
        List<T> rows = new ArrayList<>();
        boolean truncated = false;
        try (PreparedStatement statement = context.connection().prepareStatement(sql)) {
            statement.setQueryTimeout(context.timeoutSeconds());
            statement.setMaxRows(max + 1);
            statement.setInt(1, max + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                int read = 0;
                while (resultSet.next()) {
                    if (++read > max || context.budget().exhausted()) {
                        truncated = true;
                        break;
                    }
                    T row = mapper.map(resultSet);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
        } catch (SQLException | RuntimeException ex) {
            return PostgresRows.failed(describe(label, ex));
        }
        return PostgresRows.available(rows, truncated);
    }

    /** Reads a single-row statistics query; an empty result set yields an available, empty outcome. */
    static <T> PostgresRows<T> readOne(PostgresReadContext context, String label, String sql, RowMapper<T> mapper) {
        if (context.budget().exhausted()) {
            return PostgresRows.failed("The read budget ran out before " + label + " could be read.");
        }
        try (PreparedStatement statement = context.connection().prepareStatement(sql)) {
            statement.setQueryTimeout(context.timeoutSeconds());
            statement.setMaxRows(1);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return PostgresRows.available(List.of(), false);
                }
                T row = mapper.map(resultSet);
                return PostgresRows.available(row == null ? List.of() : List.of(row), false);
            }
        } catch (SQLException | RuntimeException ex) {
            return PostgresRows.failed(describe(label, ex));
        }
    }

    private static String describe(String label, Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return label + " could not be read: " + CredentialRedaction.redact(message.strip());
    }

    /** Reads a nullable {@code long} column, preserving SQL {@code NULL} as {@code null}. */
    static Long longOrNull(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    /** Reads a nullable {@code double} column, preserving SQL {@code NULL} as {@code null}. */
    static Double doubleOrNull(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    /** Reads a nullable {@code int} column, preserving SQL {@code NULL} as {@code null}. */
    static Integer intOrNull(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    /** Reads a timestamp column as epoch milliseconds, preserving SQL {@code NULL} as {@code null}. */
    static Long epochMillisOrNull(ResultSet resultSet, String column) throws SQLException {
        java.sql.Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.getTime();
    }
}
