package io.github.jdubois.bootui.engine.activity;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A minimal, dependency-free {@code DataSource} for the "dedicated" persistence mode, backed directly
 * by {@link DriverManager} rather than a connection pool.
 *
 * <p>HikariCP and Agroal are both banned from {@code bootui-engine} (they must stay behind their
 * respective adapter-side {@code ConnectionPoolProvider} implementations), so BootUI's own dedicated
 * store cannot use either. Pooling is deliberately not reimplemented here either: a flush every few
 * seconds of a small batch is low-frequency, low-concurrency traffic (at most one flush thread and one
 * request thread ever call {@link #getConnection()} at a time), so opening a plain JDBC connection per
 * call is simple, correct, and adds no dependency. Applications with sustained BootUI-only write volume
 * high enough to need pooling should point {@code bootui.activity.persistence.datasource=shared} at
 * their own pooled {@code DataSource} bean instead.</p>
 */
public final class SimpleDriverDataSource implements DataSource {

    /**
     * Bounds {@link DriverManager#getConnection}, since this dedicated mode has no connection pool
     * (unlike a {@code SHARED} pooled {@code DataSource}, which already bounds its own connection
     * acquisition) to otherwise cap how long a hung/unreachable database can block a caller —
     * including the bounded final flush {@code BufferedActivityStore#close()} attempts on shutdown.
     * {@link DriverManager#setLoginTimeout} is process-wide JDBC API, not per-{@code DataSource}; that
     * is an acceptable trade-off here since a dedicated store is BootUI's own explicit opt-in and, in
     * the common case, the only direct {@code DriverManager} user in the process.
     */
    private static final int LOGIN_TIMEOUT_SECONDS = 10;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public SimpleDriverDataSource(String jdbcUrl, String username, String password, String driverClassName) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        if (driverClassName != null && !driverClassName.isBlank()) {
            try {
                Class.forName(driverClassName);
            } catch (ClassNotFoundException ex) {
                throw new ActivityStoreException("JDBC driver class not found: " + driverClassName, ex);
            }
        }
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return username == null
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // Not supported; this DataSource does not route JDBC driver logging.
    }

    @Override
    public void setLoginTimeout(int seconds) {
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
        return DriverManager.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used by SimpleDriverDataSource");
    }
}
