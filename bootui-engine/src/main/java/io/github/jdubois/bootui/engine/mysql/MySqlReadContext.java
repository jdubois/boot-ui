package io.github.jdubois.bootui.engine.mysql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * Owns only an initially auto-commit connection. Actual session and transaction READ ONLY are required.
 * On cleanup failure abort the physical JDBC connection before closing the pool handle.
 */
final class MySqlReadContext {
    private final Connection connection;
    private final MySqlReadBudget budget;
    private int originalNetworkTimeout;
    private long originalSelectTimeout;
    private long originalLockTimeout;
    private boolean originalTransactionReadOnly;
    private boolean networkChanged;
    private boolean variablesCaptured;
    private boolean transactionOwned;

    MySqlReadContext(Connection connection, MySqlReadBudget budget) throws SQLException {
        this.connection = connection;
        this.budget = budget;
        // This is deliberately the first JDBC call after acquisition.
        if (!connection.getAutoCommit()) {
            throw new SQLException("MANUAL_COMMIT", "BUI01");
        }
        budget.selectMillis();
    }

    void open() throws SQLException {
        originalNetworkTimeout = connection.getNetworkTimeout();
        // The JDBC network guard also bounds control statements, which SELECT timeouts do not cover.
        int networkMillis = originalNetworkTimeout > 0 ? Math.min(originalNetworkTimeout, 7000) : 7000;
        networkChanged = true;
        connection.setNetworkTimeout(Runnable::run, networkMillis);
        // Do not use the JDBC read-only hint: it can reroute a replication connection and need not
        // propagate to the server. Pin the actual selected server using SQL and restore its exact state.
        Map<String, String> variables = MySqlQuery.requiredRow(
                connection,
                budget,
                "SELECT @@session.max_execution_time AS select_timeout,"
                        + " @@session.lock_wait_timeout AS lock_timeout,"
                        + " @@session.transaction_read_only AS transaction_read_only LIMIT ?");
        originalSelectTimeout = Long.parseLong(variables.get("select_timeout"));
        originalLockTimeout = Long.parseLong(variables.get("lock_timeout"));
        originalTransactionReadOnly = "1".equals(variables.get("transaction_read_only"));
        variablesCaptured = true;
        execute("SET SESSION max_execution_time=" + budget.selectMillis());
        execute("SET SESSION lock_wait_timeout=2");
        execute("SET SESSION transaction_read_only=1");
        transactionOwned = true;
        connection.setAutoCommit(false);
        execute("START TRANSACTION READ ONLY");
        Map<String, String> enforced = MySqlQuery.requiredRow(
                connection, budget, "SELECT @@session.transaction_read_only AS enforced LIMIT ?");
        if (!"1".equals(enforced.get("enforced"))) {
            throw new SQLException("READ_ONLY_NOT_ENFORCED", "BUI02");
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Returns a null reason after successful restoration, a safe explanation after discard. If abort
     * fails the handle is quarantined (not returned to its pool); the service disables further reads.
     */
    Cleanup finish() {
        // Resolve before any close/abort can invalidate the pool wrapper's delegate. Some wrappers
        // implement abort by closing only their logical handle, leaving the physical session and pool
        // slot alive. Standard JDBC unwrapping avoids optional pool APIs or reflective eviction.
        Connection abortTarget = abortTarget();
        try {
            if (transactionOwned) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            if (variablesCaptured) {
                execute("SET SESSION max_execution_time=" + originalSelectTimeout);
                execute("SET SESSION lock_wait_timeout=" + originalLockTimeout);
                execute("SET SESSION transaction_read_only=" + (originalTransactionReadOnly ? 1 : 0));
            }
            if (networkChanged) {
                connection.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
            }
            connection.close();
            return new Cleanup(null, false);
        } catch (SQLException | RuntimeException restoreFailure) {
            try {
                abortTarget.abort(Runnable::run);
            } catch (SQLException | RuntimeException abortFailure) {
                // Closing this logical handle might return contaminated state to an application pool.
                return new Cleanup(
                        "Session restoration and JDBC abort failed. The connection is quarantined; restart the pool.",
                        true);
            }
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeFailure) {
                // The physical abort already succeeded. Hikari may report 08003 while resetting the
                // closed connection, then evict/recycle it in finally. Never call this an abort failure.
            }
            return new Cleanup("Session restoration failed; the JDBC connection was aborted and discarded.", false);
        }
    }

    private Connection abortTarget() {
        try {
            Connection physical = connection.unwrap(Connection.class);
            return physical == null ? connection : physical;
        } catch (SQLException | RuntimeException unsupported) {
            // A direct JDBC connection or a wrapper without unwrap still has the standard abort contract.
            return connection;
        }
    }

    record Cleanup(String reason, boolean quarantined) {}
}
