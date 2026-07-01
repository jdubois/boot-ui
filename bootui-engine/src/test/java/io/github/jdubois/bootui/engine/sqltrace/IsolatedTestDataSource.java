package io.github.jdubois.bootui.engine.sqltrace;

import java.io.PrintWriter;
import java.sql.Connection;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Minimal {@link DataSource} used only by {@code SqlTracingProxiesTests} to reproduce the Spring Boot
 * DevTools class-loader split. It is a standalone top-level class (no reference to any BootUI type) so a
 * test class loader can define it in isolation, mimicking a driver/pool data source whose loader cannot
 * see BootUI's {@code SqlTracedDataSource} marker.
 */
public class IsolatedTestDataSource implements DataSource {

    @Override
    public Connection getConnection() {
        return null;
    }

    @Override
    public Connection getConnection(String username, String password) {
        return null;
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
