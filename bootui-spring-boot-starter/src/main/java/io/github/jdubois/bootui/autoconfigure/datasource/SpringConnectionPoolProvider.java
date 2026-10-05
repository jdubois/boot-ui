package io.github.jdubois.bootui.autoconfigure.datasource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.jdubois.bootui.autoconfigure.web.HikariDataSourceDiscovery;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.ConnectionPoolSnapshot;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Spring binding of the {@link ConnectionPoolProvider} SPI: it discovers the application's
 * {@code com.zaxxer.hikari.HikariDataSource} beans (directly, and through proxied/wrapped {@code DataSource}
 * beans) and reads each pool's configuration getters plus the live {@link HikariPoolMXBean} counters.
 *
 * <p>This is the byte-identical extraction of the former {@code DatabaseConnectionPoolsController} discovery
 * and reads: the pool sizing/timeout getters, the {@code safeXxx} swallow-and-default helpers, the
 * {@code snapshotOf} MXBean read, and the {@code unavailableReason} text are reproduced verbatim. It returns
 * the <em>raw</em>, unmasked JDBC URL and username; the engine {@code ConnectionPoolService} masks them
 * through the exposure policy, so the Spring panel's wire output is unchanged.</p>
 *
 * <p>It is the sole HikariCP importer on the Spring side and is only constructed in the nested
 * {@code @ConditionalOnClass(HikariDataSource.class)} backend configuration, so {@code com.zaxxer.hikari}
 * types are never linked in a pool-absent application.</p>
 */
public final class SpringConnectionPoolProvider implements ConnectionPoolProvider {

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    public SpringConnectionPoolProvider(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this.beanFactoryProvider = beanFactoryProvider;
    }

    @Override
    public List<ConnectionPoolInfo> pools() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        return HikariDataSourceDiscovery.discover(factory).stream()
                .map(this::toInfo)
                .toList();
    }

    private ConnectionPoolInfo toInfo(HikariDataSourceDiscovery.PoolEntry entry) {
        HikariDataSource dataSource = entry.dataSource();
        String poolName = safeString(dataSource::getPoolName);
        String jdbcUrl = safeString(dataSource::getJdbcUrl);
        String username = safeString(dataSource::getUsername);
        String driverClassName = safeString(dataSource::getDriverClassName);
        int minimumIdle = safeInt(dataSource::getMinimumIdle);
        int maximumPoolSize = safeInt(dataSource::getMaximumPoolSize);
        long connectionTimeout = safeLong(dataSource::getConnectionTimeout);
        long idleTimeout = safeLong(dataSource::getIdleTimeout);
        long maxLifetime = safeLong(dataSource::getMaxLifetime);
        long validationTimeout = safeLong(dataSource::getValidationTimeout);
        long keepaliveTime = safeLong(dataSource::getKeepaliveTime);
        boolean readOnly = safeBoolean(dataSource::isReadOnly);
        boolean autoCommit = safeBoolean(dataSource::isAutoCommit);

        ConnectionPoolSnapshot snapshot = snapshotOf(dataSource);
        boolean available = snapshot != null;
        String unavailableReason = available ? null : unavailableReason(dataSource);

        return new ConnectionPoolInfo(
                entry.beanName(),
                poolName,
                jdbcUrl,
                username,
                driverClassName,
                minimumIdle,
                maximumPoolSize,
                connectionTimeout,
                idleTimeout,
                maxLifetime,
                validationTimeout,
                keepaliveTime,
                readOnly,
                autoCommit,
                available,
                unavailableReason,
                snapshot);
    }

    @Nullable
    private ConnectionPoolSnapshot snapshotOf(HikariDataSource dataSource) {
        HikariPoolMXBean mxBean;
        try {
            mxBean = dataSource.getHikariPoolMXBean();
        } catch (Exception ex) {
            return null;
        }
        if (mxBean == null) {
            return null;
        }
        try {
            return new ConnectionPoolSnapshot(
                    System.currentTimeMillis(),
                    mxBean.getActiveConnections(),
                    mxBean.getIdleConnections(),
                    mxBean.getTotalConnections(),
                    mxBean.getThreadsAwaitingConnection());
        } catch (Exception ex) {
            return null;
        }
    }

    private String unavailableReason(HikariDataSource dataSource) {
        try {
            if (dataSource.isClosed()) {
                return "Pool is closed";
            }
        } catch (Exception ex) {
            // fall through to the generic reason below
        }
        return "Pool is not initialized or its runtime MXBean is unavailable";
    }

    @Nullable
    private String safeString(Supplier<String> getter) {
        try {
            return getter.get();
        } catch (Exception ex) {
            return null;
        }
    }

    private int safeInt(IntSupplier getter) {
        try {
            return getter.getAsInt();
        } catch (Exception ex) {
            return -1;
        }
    }

    private long safeLong(LongSupplier getter) {
        try {
            return getter.getAsLong();
        } catch (Exception ex) {
            return -1;
        }
    }

    private boolean safeBoolean(BooleanSupplier getter) {
        try {
            return getter.getAsBoolean();
        } catch (Exception ex) {
            return false;
        }
    }
}
