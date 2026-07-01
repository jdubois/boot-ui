package io.github.jdubois.bootui.engine.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HikariPoolSnapshotDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.ConnectionPoolSnapshot;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the framework-neutral {@link ConnectionPoolService}: the masking states (driven by both
 * {@code valueExposure()} and {@code maskSecrets()}), pool sorting, the null-provider (pool-absent) path, the
 * snapshot lookup, and the metrics-disabled (null snapshot) carry-through. The provider and exposure policy
 * are simple in-test fakes, so this exercises only the engine's neutral assembly.
 */
class ConnectionPoolServiceTests {

    private static final String RAW_URL = "jdbc:postgresql://" + "app" + ":" + "examplepass" + "@localhost:5432/demo";

    private static ConnectionPoolInfo poolInfo(String beanName, String poolName, ConnectionPoolSnapshot snapshot) {
        boolean available = snapshot != null;
        return new ConnectionPoolInfo(
                beanName,
                poolName,
                RAW_URL,
                "app",
                "org.postgresql.Driver",
                5,
                10,
                30000L,
                600000L,
                1800000L,
                -1L,
                -1L,
                false,
                true,
                available,
                available ? null : "Pool metrics are disabled",
                snapshot);
    }

    private static ConnectionPoolService service(ExposurePolicy exposure, ConnectionPoolInfo... pools) {
        ConnectionPoolProvider provider = () -> List.of(pools);
        return new ConnectionPoolService(provider, exposure);
    }

    @Test
    void reportMasksCredentialsWhenMaskedAndMaskSecrets() {
        ConnectionPoolService service = service(
                exposure(ValueExposure.MASKED, true),
                poolInfo("dataSource", "pool-1", new ConnectionPoolSnapshot(1L, 3, 2, 5, 1)));

        HikariPoolsReport report = service.report();

        assertThat(report.hikariPresent()).isTrue();
        assertThat(report.total()).isEqualTo(1);
        HikariPoolDto pool = report.pools().get(0);
        assertThat(pool.jdbcUrl()).isEqualTo("jdbc:postgresql://******@localhost:5432/demo");
        assertThat(pool.username()).isEqualTo("******");
        assertThat(pool.validationTimeoutMs()).isEqualTo(-1L);
        assertThat(pool.keepaliveTimeMs()).isEqualTo(-1L);
        assertThat(pool.available()).isTrue();
        assertThat(pool.snapshot().active()).isEqualTo(3);
        assertThat(pool.snapshot().idle()).isEqualTo(2);
        assertThat(pool.snapshot().total()).isEqualTo(5);
        assertThat(pool.snapshot().pending()).isEqualTo(1);
    }

    @Test
    void reportExposesRawCredentialsWhenExposureIsFull() {
        ConnectionPoolService service = service(
                exposure(ValueExposure.FULL, true),
                poolInfo("dataSource", "pool-1", new ConnectionPoolSnapshot(1L, 0, 0, 0, 0)));

        HikariPoolDto pool = service.report().pools().get(0);

        assertThat(pool.jdbcUrl()).isEqualTo(RAW_URL);
        assertThat(pool.username()).isEqualTo("app");
    }

    @Test
    void reportExposesRawCredentialsWhenMaskedButMaskSecretsDisabled() {
        ConnectionPoolService service = service(
                exposure(ValueExposure.MASKED, false),
                poolInfo("dataSource", "pool-1", new ConnectionPoolSnapshot(1L, 0, 0, 0, 0)));

        HikariPoolDto pool = service.report().pools().get(0);

        assertThat(pool.jdbcUrl()).isEqualTo(RAW_URL);
        assertThat(pool.username()).isEqualTo("app");
    }

    @Test
    void reportHidesCredentialsWhenMetadataOnly() {
        ConnectionPoolService service = service(
                exposure(ValueExposure.METADATA_ONLY, true),
                poolInfo("dataSource", "pool-1", new ConnectionPoolSnapshot(1L, 0, 0, 0, 0)));

        HikariPoolDto pool = service.report().pools().get(0);

        assertThat(pool.jdbcUrl()).isNull();
        assertThat(pool.username()).isNull();
    }

    @Test
    void reportSortsPoolsByName() {
        ConnectionPoolService service = service(
                exposure(ValueExposure.MASKED, true),
                poolInfo("z", "zeta", new ConnectionPoolSnapshot(1L, 0, 0, 0, 0)),
                poolInfo("a", "alpha", new ConnectionPoolSnapshot(1L, 0, 0, 0, 0)));

        HikariPoolsReport report = service.report();

        assertThat(report.pools()).extracting(HikariPoolDto::poolName).containsExactly("alpha", "zeta");
    }

    @Test
    void reportCarriesMetricsDisabledPoolWithNullSnapshot() {
        ConnectionPoolService service =
                service(exposure(ValueExposure.MASKED, true), poolInfo("dataSource", "pool-1", null));

        HikariPoolDto pool = service.report().pools().get(0);

        assertThat(pool.available()).isFalse();
        assertThat(pool.snapshot()).isNull();
        assertThat(pool.unavailableReason()).isEqualTo("Pool metrics are disabled");
    }

    @Test
    void reportIsEmptyAndAbsentWhenProviderIsNull() {
        ConnectionPoolService service = new ConnectionPoolService(null, exposure(ValueExposure.MASKED, true));

        HikariPoolsReport report = service.report();

        assertThat(report.hikariPresent()).isFalse();
        assertThat(report.total()).isZero();
        assertThat(report.pools()).isEmpty();
    }

    @Test
    void snapshotMatchesByPoolNameOrBeanName() {
        ConnectionPoolService service = service(
                exposure(ValueExposure.MASKED, true),
                poolInfo("dataSource", "pool-1", new ConnectionPoolSnapshot(1L, 3, 2, 5, 1)));

        HikariPoolSnapshotDto byPoolName = service.snapshot("pool-1");
        HikariPoolSnapshotDto byBeanName = service.snapshot("dataSource");

        assertThat(byPoolName).isNotNull();
        assertThat(byPoolName.active()).isEqualTo(3);
        assertThat(byBeanName).isNotNull();
        assertThat(byBeanName.total()).isEqualTo(5);
    }

    @Test
    void snapshotReturnsNullForUnknownPoolAndNullProvider() {
        ConnectionPoolService present = service(
                exposure(ValueExposure.MASKED, true),
                poolInfo("dataSource", "pool-1", new ConnectionPoolSnapshot(1L, 3, 2, 5, 1)));
        ConnectionPoolService absent = new ConnectionPoolService(null, exposure(ValueExposure.MASKED, true));

        assertThat(present.snapshot("does-not-exist")).isNull();
        assertThat(absent.snapshot("pool-1")).isNull();
    }

    private static ExposurePolicy exposure(ValueExposure exposure, boolean maskSecrets) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
    }
}
