package io.github.jdubois.bootui.engine.datasource;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HikariPoolSnapshotDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.ConnectionPoolSnapshot;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Framework-neutral logic behind the Database Connection Pools panel, shared by the Spring Boot and Quarkus
 * adapters.
 *
 * <p>It reads the host application's connection pools through a {@link ConnectionPoolProvider} (the
 * framework-specific seam: HikariCP on Spring, Agroal on Quarkus) and owns everything neutral — masking the
 * JDBC URL and pool username through the {@link ExposurePolicy} SPI, sorting the pools by name, counting, and
 * assembling the wire {@link HikariPoolsReport} / per-pool {@link HikariPoolSnapshotDto}.</p>
 *
 * <p>The masking is the byte-identical move of the former {@code DatabaseConnectionPoolsController} logic: the
 * two URL-credential regexes and the {@link SecretMasker#MASKED_VALUE} username substitution are reproduced
 * verbatim, and both {@link ExposurePolicy#valueExposure()} and {@link ExposurePolicy#maskSecrets()} are
 * honored exactly as before (so a Spring user running {@code expose-values=MASKED} with
 * {@code mask-secrets=false} still sees raw values), keeping the Spring panel's wire contract unchanged.</p>
 *
 * <p>The DTO type names are HikariCP-flavored for historical reasons; their fields are generic pool metrics
 * and the shared Vue UI is framework-neutral, so the Quarkus adapter maps Agroal pools into the same shape
 * (a deliberate kept-contract decision, mirroring the Cache panel sharing the {@code cache} id).</p>
 */
public final class ConnectionPoolService {

    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("([a-z][a-z0-9+.-]*://)([^:/@\\s]+):([^@\\s]+)@", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_CREDENTIAL_PARAMS =
            Pattern.compile("([?&](?:user|username|password|passwd|pwd)=)([^&\\s]*)", Pattern.CASE_INSENSITIVE);

    private final ConnectionPoolProvider provider;

    private final ExposurePolicy exposure;

    public ConnectionPoolService(ConnectionPoolProvider provider, ExposurePolicy exposure) {
        this.provider = provider;
        this.exposure = exposure;
    }

    /** The pool report: every pool's masked metadata, sizing/timeout settings and latest snapshot. */
    public HikariPoolsReport report() {
        if (provider == null) {
            return new HikariPoolsReport(false, 0, List.of());
        }
        List<HikariPoolDto> pools = provider.pools().stream()
                .map(this::toDto)
                .sorted(Comparator.comparing(HikariPoolDto::poolName, Comparator.nullsLast(String::compareTo)))
                .toList();
        return new HikariPoolsReport(true, pools.size(), pools);
    }

    /** The latest snapshot for one pool, matched by pool name or bean name, or {@code null} when not found. */
    public HikariPoolSnapshotDto snapshot(String name) {
        if (provider == null || name == null) {
            return null;
        }
        for (ConnectionPoolInfo pool : provider.pools()) {
            if (name.equals(pool.beanName()) || name.equals(pool.poolName())) {
                return toSnapshotDto(pool.snapshot());
            }
        }
        return null;
    }

    private HikariPoolDto toDto(ConnectionPoolInfo pool) {
        return new HikariPoolDto(
                pool.beanName(),
                pool.poolName(),
                maskUrl(pool.jdbcUrl()),
                maskUsername(pool.username()),
                pool.driverClassName(),
                pool.minimumIdle(),
                pool.maximumPoolSize(),
                pool.connectionTimeoutMs(),
                pool.idleTimeoutMs(),
                pool.maxLifetimeMs(),
                pool.validationTimeoutMs(),
                pool.keepaliveTimeMs(),
                pool.readOnly(),
                pool.autoCommit(),
                pool.available(),
                pool.unavailableReason(),
                toSnapshotDto(pool.snapshot()));
    }

    private HikariPoolSnapshotDto toSnapshotDto(ConnectionPoolSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new HikariPoolSnapshotDto(
                snapshot.timestamp(), snapshot.active(), snapshot.idle(), snapshot.total(), snapshot.pending());
    }

    private String maskUrl(String url) {
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

    private String maskUsername(String username) {
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
}
