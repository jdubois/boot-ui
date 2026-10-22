package io.github.jdubois.bootui.autoconfigure.datasource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.jdubois.bootui.autoconfigure.web.HikariDataSourceDiscovery;
import io.github.jdubois.bootui.engine.datasource.PhysicalDataSources;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.ConnectionPoolSnapshot;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.SingletonBeanRegistry;

/**
 * Spring binding of the {@link ConnectionPoolProvider} SPI: it discovers the application's
 * {@code com.zaxxer.hikari.HikariDataSource} beans (directly, and through proxied/wrapped {@code DataSource}
 * beans) and reads each pool's configuration getters plus the live {@link HikariPoolMXBean} counters.
 *
 * <p>It reads the pool sizing/timeout getters through {@code safeXxx} helpers that report an unreadable setting as
 * {@code null} (or {@code -1} for a pool size), the {@code snapshotOf} MXBean read, and the
 * {@code unavailableReason} text. It returns
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

    /**
     * Matches {@code dataSource} to a Hikari pool by identity, after looking through BootUI's SQL Trace proxy,
     * Spring's single-target {@code DataSource} wrappers and static AOP proxies. A wrapper with a second, read-only
     * target, a dynamic AOP target, a routing wrapper, or any other pool library is not a match.
     *
     * <p>Only beans that already exist are read. The pool Hibernate holds is necessarily one of them, so an unrelated
     * lazy datasource is never created, and nothing here borrows a connection.</p>
     */
    @Override
    public Optional<ConnectionPoolInfo> poolFor(DataSource dataSource) {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null || dataSource == null) {
            return Optional.empty();
        }
        DataSource physical = PhysicalDataSources.physical(dataSource, SpringConnectionPoolProvider::wrappedTarget);
        if (!(physical instanceof HikariDataSource pool)) {
            return Optional.empty();
        }
        String beanName = existingBeanName(factory, HikariDataSource.class, pool);
        if (beanName == null) {
            beanName = existingBeanName(factory, DataSource.class, pool);
        }
        return beanName == null
                ? Optional.empty()
                : Optional.of(toInfo(new HikariDataSourceDiscovery.PoolEntry(beanName, pool)));
    }

    private static String existingBeanName(ListableBeanFactory factory, Class<?> type, HikariDataSource pool) {
        String[] names;
        try {
            names = factory.getBeanNamesForType(type);
        } catch (BeansException ex) {
            return null;
        }
        for (String name : names == null ? new String[0] : names) {
            String beanName = name.startsWith("&") ? name.substring(1) : name;
            if (factory instanceof SingletonBeanRegistry registry && !registry.containsSingleton(beanName)) {
                continue;
            }
            try {
                Object bean = factory.getBean(beanName, type);
                if (bean instanceof DataSource candidate
                        && PhysicalDataSources.physical(candidate, SpringConnectionPoolProvider::wrappedTarget)
                                == pool) {
                    return beanName;
                }
            } catch (BeansException ex) {
                // A bean that cannot be read is not evidence for or against this pool.
            }
        }
        return null;
    }

    private static DataSource wrappedTarget(DataSource dataSource) {
        if (DelegatingDataSources.isSingleTarget(dataSource.getClass())) {
            return DelegatingDataSources.hasDistinctReadOnlyTarget(dataSource)
                    ? null
                    : DelegatingDataSources.target(dataSource);
        }
        return SpringAopDataSources.isProxy(dataSource) ? SpringAopDataSources.staticTarget(dataSource) : null;
    }

    private ConnectionPoolInfo toInfo(HikariDataSourceDiscovery.PoolEntry entry) {
        HikariDataSource dataSource = entry.dataSource();
        String poolName = safeString(dataSource::getPoolName);
        String jdbcUrl = safeString(dataSource::getJdbcUrl);
        String username = safeString(dataSource::getUsername);
        String driverClassName = safeString(dataSource::getDriverClassName);
        int minimumIdle = safeInt(dataSource::getMinimumIdle);
        int maximumPoolSize = safeInt(dataSource::getMaximumPoolSize);
        Long connectionTimeout = safeLong(dataSource::getConnectionTimeout);
        Long idleTimeout = safeLong(dataSource::getIdleTimeout);
        Long maxLifetime = safeLong(dataSource::getMaxLifetime);
        Long validationTimeout = safeLong(dataSource::getValidationTimeout);
        Long keepaliveTime = safeLong(dataSource::getKeepaliveTime);
        Boolean readOnly = safeBoolean(dataSource::isReadOnly);
        Boolean autoCommit = safeBoolean(dataSource::isAutoCommit);

        ConnectionPoolSnapshot snapshot = snapshotOf(dataSource);
        boolean available = snapshot != null;
        String unavailableReason = available ? null : unavailableReason(dataSource);

        return new ConnectionPoolInfo(
                entry.beanName(),
                poolName,
                ConnectionPoolInfo.HIKARI,
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

    @Nullable
    private Long safeLong(LongSupplier getter) {
        try {
            return getter.getAsLong();
        } catch (Exception ex) {
            return null;
        }
    }

    @Nullable
    private Boolean safeBoolean(BooleanSupplier getter) {
        try {
            return getter.getAsBoolean();
        } catch (Exception ex) {
            return null;
        }
    }
}
