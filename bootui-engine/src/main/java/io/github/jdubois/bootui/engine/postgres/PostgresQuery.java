package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
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
 *
 * <p>Each query also runs inside its own savepoint. PostgreSQL aborts the entire transaction on <em>any</em>
 * statement error, so without one, a single missing column, refused view or fired {@code statement_timeout}
 * would leave every later section reporting "current transaction is aborted" instead of its own content —
 * one absent extension would masquerade as a database-wide failure. Rolling back to the savepoint contains
 * the failure to the query that caused it.</p>
 */
final class PostgresQuery {

    @FunctionalInterface
    interface RowMapper<T> {
        /** Maps the current row, or returns {@code null} to skip it. */
        T map(ResultSet resultSet) throws SQLException;
    }

    private PostgresQuery() {}

    /** Reads a bounded list from a statement whose only placeholder is a trailing {@code limit ?}. */
    static <T> PostgresRows<T> readList(
            PostgresReadContext context, String label, String sql, int max, RowMapper<T> mapper) {
        if (context.budget().exhausted()) {
            return PostgresRows.failed("The read budget ran out before " + label + " could be read.");
        }
        Savepoint savepoint = savepoint(context.connection());
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
            rollback(context.connection(), savepoint);
            return PostgresRows.failed(describe(label, ex));
        }
        release(context.connection(), savepoint);
        return PostgresRows.available(rows, truncated);
    }

    /** Reads a single-row statistics query; an empty result set yields an available, empty outcome. */
    static <T> PostgresRows<T> readOne(PostgresReadContext context, String label, String sql, RowMapper<T> mapper) {
        if (context.budget().exhausted()) {
            return PostgresRows.failed("The read budget ran out before " + label + " could be read.");
        }
        Savepoint savepoint = savepoint(context.connection());
        PostgresRows<T> outcome;
        try (PreparedStatement statement = context.connection().prepareStatement(sql)) {
            statement.setQueryTimeout(context.timeoutSeconds());
            statement.setMaxRows(1);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    outcome = PostgresRows.available(List.of(), false);
                } else {
                    T row = mapper.map(resultSet);
                    outcome = PostgresRows.available(row == null ? List.of() : List.of(row), false);
                }
            }
        } catch (SQLException | RuntimeException ex) {
            rollback(context.connection(), savepoint);
            return PostgresRows.failed(describe(label, ex));
        }
        release(context.connection(), savepoint);
        return outcome;
    }

    /**
     * Executes one session-pinning statement in its own savepoint, returning {@code null} on success or the
     * redacted reason it failed.
     *
     * <p>The pins run before any collector does, on the transaction every later query shares. A pin that
     * fails aborts that transaction, and an aborted transaction refuses even {@code SAVEPOINT}, so without
     * this isolation one rejected {@code SET} would make every section report a transaction error instead of
     * its own content — the exact failure the per-query savepoints exist to prevent.</p>
     */
    static String pin(Connection connection, String sql) {
        Savepoint savepoint = savepoint(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException | RuntimeException ex) {
            rollback(connection, savepoint);
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return CredentialRedaction.redact(message.strip());
        }
        release(connection, savepoint);
        return null;
    }

    /**
     * The savepoint a failing query is rolled back to, or {@code null} when the connection would not give
     * one. A connection running in auto-commit — which only happens when pinning the read-only transaction
     * failed — has nothing to abort, so the absence is not itself an error.
     */
    private static Savepoint savepoint(Connection connection) {
        try {
            return connection.getAutoCommit() ? null : connection.setSavepoint();
        } catch (SQLException | RuntimeException ex) {
            return null;
        }
    }

    /** Contains a failed query: the transaction stays usable for the sections that come after it. */
    private static void rollback(Connection connection, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            connection.rollback(savepoint);
        } catch (SQLException | RuntimeException ex) {
            // The transaction is already unusable; the next query reports its own failure.
        }
    }

    /** Drops a savepoint that was not needed, so a long read does not accumulate subtransactions. */
    private static void release(Connection connection, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException | RuntimeException ex) {
            // Releasing is an optimization; an unreleased savepoint costs a little memory and nothing else.
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
