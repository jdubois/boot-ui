package io.github.jdubois.bootui.autoconfigure.web;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HikariPoolSnapshotDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import jakarta.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes read-only database connection-pool state for supported JDBC pool beans
 * declared in the current application context.
 *
 * <p>This controller is strictly read-only: it only reads pool configuration
 * getters and the live counters published by {@link HikariPoolMXBean}. It never
 * borrows a connection, executes SQL, or mutates the pool (no resize, suspend,
 * or evict). Credentials embedded in the JDBC URL and the pool username are
 * routed through {@link SecretMasker} before they reach the browser.</p>
 */
@RestController
@ConditionalOnClass(HikariDataSource.class)
@RequestMapping("/bootui/api/database-connection-pools")
public class HikariController {

    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("([a-z][a-z0-9+.-]*://)([^:/@\\s]+):([^@\\s]+)@", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_CREDENTIAL_PARAMS =
            Pattern.compile("([?&](?:user|username|password|passwd|pwd)=)([^&\\s]*)", Pattern.CASE_INSENSITIVE);

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;
    private final BootUiExposure exposure;

    @Autowired
    public HikariController(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            BootUiProperties properties,
            BootUiExposure exposure) {
        this.beanFactoryProvider = beanFactoryProvider;
        this.exposure = exposure;
    }

    HikariController(ObjectProvider<ListableBeanFactory> beanFactoryProvider, BootUiProperties properties) {
        this(beanFactoryProvider, properties, new BootUiExposure(properties));
    }

    @GetMapping("/pools")
    public HikariPoolsReport pools() {
        List<HikariDataSourceDiscovery.PoolEntry> entries = discover();
        List<HikariPoolDto> pools = entries.stream()
                .map(this::toDto)
                .sorted(Comparator.comparing(HikariPoolDto::poolName, Comparator.nullsLast(String::compareTo)))
                .toList();
        return new HikariPoolsReport(true, pools.size(), pools);
    }

    @GetMapping("/pools/{name}/snapshot")
    public ResponseEntity<HikariPoolSnapshotDto> snapshot(@PathVariable String name) {
        for (HikariDataSourceDiscovery.PoolEntry entry : discover()) {
            if (matches(entry, name)) {
                HikariPoolSnapshotDto snapshot = snapshotOf(entry.dataSource());
                if (snapshot == null) {
                    return ResponseEntity.notFound().build();
                }
                return ResponseEntity.ok(snapshot);
            }
        }
        return ResponseEntity.notFound().build();
    }

    private List<HikariDataSourceDiscovery.PoolEntry> discover() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        return HikariDataSourceDiscovery.discover(factory);
    }

    private boolean matches(HikariDataSourceDiscovery.PoolEntry entry, String name) {
        if (name == null) {
            return false;
        }
        if (name.equals(entry.beanName())) {
            return true;
        }
        try {
            return name.equals(entry.dataSource().getPoolName());
        } catch (Exception ex) {
            return false;
        }
    }

    private HikariPoolDto toDto(HikariDataSourceDiscovery.PoolEntry entry) {
        HikariDataSource dataSource = entry.dataSource();
        String poolName = safeString(dataSource::getPoolName);
        String jdbcUrl = maskUrl(safeString(dataSource::getJdbcUrl));
        String username = maskUsername(safeString(dataSource::getUsername));
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

        HikariPoolSnapshotDto snapshot = snapshotOf(dataSource);
        boolean available = snapshot != null;
        String unavailableReason = available ? null : unavailableReason(dataSource);

        return new HikariPoolDto(
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
    private HikariPoolSnapshotDto snapshotOf(HikariDataSource dataSource) {
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
            return new HikariPoolSnapshotDto(
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
    private String maskUrl(@Nullable String url) {
        if (url == null) {
            return null;
        }
        ValueExposure valueExposure = exposure.valueExposure();
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (valueExposure == ValueExposure.FULL || !exposure.maskSecrets()) {
            return url;
        }
        String masked = URL_CREDENTIALS.matcher(url).replaceAll("$1******@");
        masked = URL_CREDENTIAL_PARAMS.matcher(masked).replaceAll("$1" + SecretMasker.MASKED_VALUE);
        return masked;
    }

    @Nullable
    private String maskUsername(@Nullable String username) {
        if (username == null) {
            return null;
        }
        ValueExposure valueExposure = exposure.valueExposure();
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (valueExposure == ValueExposure.FULL || !exposure.maskSecrets()) {
            return username;
        }
        return SecretMasker.MASKED_VALUE;
    }

    @Nullable
    private String safeString(java.util.function.Supplier<String> getter) {
        try {
            return getter.get();
        } catch (Exception ex) {
            return null;
        }
    }

    private int safeInt(java.util.function.IntSupplier getter) {
        try {
            return getter.getAsInt();
        } catch (Exception ex) {
            return -1;
        }
    }

    private long safeLong(java.util.function.LongSupplier getter) {
        try {
            return getter.getAsLong();
        } catch (Exception ex) {
            return -1;
        }
    }

    private boolean safeBoolean(java.util.function.BooleanSupplier getter) {
        try {
            return getter.getAsBoolean();
        } catch (Exception ex) {
            return false;
        }
    }
}
