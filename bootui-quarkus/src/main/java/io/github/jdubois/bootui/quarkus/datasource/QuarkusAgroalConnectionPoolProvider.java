package io.github.jdubois.bootui.quarkus.datasource;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import io.agroal.api.configuration.AgroalConnectionFactoryConfiguration;
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.ConnectionPoolSnapshot;
import io.quarkus.agroal.runtime.AgroalDataSourceUtil;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Quarkus/Agroal binding of the {@link ConnectionPoolProvider} SPI: it enumerates the application's active
 * Agroal datasources and maps each pool's {@code io.agroal.api} configuration and live
 * {@link AgroalDataSourceMetrics} onto the framework-neutral {@link ConnectionPoolInfo} the engine assembles.
 *
 * <p>It is the <strong>sole</strong> importer of the {@code io.agroal.*} API in BootUI and is constructed only
 * by {@link io.github.jdubois.bootui.quarkus.BootUiAgroalProducer}, which the deployment processor excludes
 * from bean discovery unless the {@code AGROAL} capability is present (R2) — so the {@code io.agroal} types are
 * never linked in a datasource-absent application.</p>
 *
 * <p>The shared DTO types are HikariCP-named for historical reasons; their fields are generic pool metrics and each
 * pool reports {@code implementation=Agroal}. The mapping: active←{@code activeCount}, idle←{@code availableCount},
 * total←active+idle, pending←{@code awaitingCount}; connection timeout←acquisition timeout, idle timeout←reap
 * timeout, plus max-lifetime (zero, Agroal's default, means no limit), min-size and max-size. Agroal has no
 * faithful analogue of HikariCP's per-call validation timeout, keepalive interval, or read-only flag, so those
 * are {@code null} (the UI renders "—"); the JDBC driver class is often unknown at runtime and is then
 * {@code null}.</p>
 *
 * <p>Live counts are only available when {@code quarkus.datasource.jdbc.metrics.enabled=true}: when metrics are
 * disabled the configuration still renders, but the snapshot is {@code null}, the pool is marked unavailable,
 * and a specific reason points the operator at the metrics flag.</p>
 */
public final class QuarkusAgroalConnectionPoolProvider implements ConnectionPoolProvider {

    private static final String METRICS_DISABLED_REASON =
            "Pool metrics are disabled; set quarkus.datasource.jdbc.metrics.enabled=true to see live connection counts";

    @Override
    public List<ConnectionPoolInfo> pools() {
        List<ConnectionPoolInfo> pools = new ArrayList<>();
        for (String name : AgroalDataSourceUtil.activeDataSourceNames()) {
            Optional<AgroalDataSource> dataSource = dataSourceIfActive(name);
            dataSource.ifPresent(agroal -> pools.add(toInfo(name, agroal)));
        }
        return pools;
    }

    private Optional<AgroalDataSource> dataSourceIfActive(String name) {
        try {
            return AgroalDataSourceUtil.dataSourceIfActive(name);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private ConnectionPoolInfo toInfo(String dataSourceName, AgroalDataSource dataSource) {
        String displayName = displayName(dataSourceName);

        AgroalDataSourceConfiguration config = dataSource.getConfiguration();
        AgroalConnectionPoolConfiguration poolConfig = config.connectionPoolConfiguration();
        AgroalConnectionFactoryConfiguration factoryConfig = poolConfig.connectionFactoryConfiguration();

        String jdbcUrl = factoryConfig.jdbcUrl();
        String username = principalName(factoryConfig.principal());
        String driverClassName = factoryConfig.connectionProviderClass() == null
                ? null
                : factoryConfig.connectionProviderClass().getName();
        int minimumIdle = poolConfig.minSize();
        int maximumPoolSize = poolConfig.maxSize();
        Long connectionTimeout = millis(poolConfig.acquisitionTimeout());
        Long idleTimeout = millis(poolConfig.reapTimeout());
        Long maxLifetime = millis(poolConfig.maxLifetime());
        boolean autoCommit = factoryConfig.autoCommit();

        ConnectionPoolSnapshot snapshot = snapshotOf(config, dataSource);
        boolean available = snapshot != null;
        String unavailableReason = available ? null : METRICS_DISABLED_REASON;

        return new ConnectionPoolInfo(
                displayName,
                displayName,
                ConnectionPoolInfo.AGROAL,
                jdbcUrl,
                username,
                driverClassName,
                minimumIdle,
                maximumPoolSize,
                connectionTimeout,
                idleTimeout,
                maxLifetime,
                null,
                null,
                null,
                autoCommit,
                available,
                unavailableReason,
                snapshot);
    }

    private ConnectionPoolSnapshot snapshotOf(AgroalDataSourceConfiguration config, AgroalDataSource dataSource) {
        if (!config.metricsEnabled()) {
            return null;
        }
        try {
            AgroalDataSourceMetrics metrics = dataSource.getMetrics();
            int active = (int) metrics.activeCount();
            int idle = (int) metrics.availableCount();
            int pending = (int) metrics.awaitingCount();
            return new ConnectionPoolSnapshot(System.currentTimeMillis(), active, idle, active + idle, pending);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String displayName(String dataSourceName) {
        return DataSourceUtil.isDefault(dataSourceName) ? "default" : dataSourceName;
    }

    private String principalName(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    private Long millis(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }
}
