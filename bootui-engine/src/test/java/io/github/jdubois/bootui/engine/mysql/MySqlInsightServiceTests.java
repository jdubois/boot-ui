package io.github.jdubois.bootui.engine.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MySqlInsightServiceTests {
    @ParameterizedTest
    @ValueSource(strings = {" AS select_timeout", " AS enforced", "@@version AS version"})
    void requiredMetadataBudgetExpiryKeepsTheTimeoutReasonAndRestoresTheConnection(String marker) throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        AtomicLong time = new AtomicLong();
        fixture.beforeQuery = () -> {
            if (fixture.sql.get(fixture.sql.size() - 1).contains(marker)) {
                time.set(11_000_000);
            }
        };
        MySqlInsightService service = new MySqlInsightService(
                () -> inventory(fixture),
                null,
                Clock.systemUTC(),
                MySqlRowLimits.defaults(),
                time::get,
                Duration.ofMillis(10));
        var report = service.read();
        assertThat(report.status()).isEqualTo("ERROR");
        assertThat(report.dataSources()).singleElement().satisfies(source -> {
            assertThat(source.message()).contains("Time budget").doesNotContain("Collection failed");
            assertThat(source.sections()).isEmpty();
        });
        assertThat(fixture.networkTimeout).isEqualTo(12000);
        assertThat(fixture.autoCommit).isTrue();
        verify(fixture.connection).close();
        verify(fixture.connection, never()).commit();
        if (marker.equals(" AS select_timeout")) {
            verify(fixture.connection, never()).rollback();
            assertThat(fixture.sql).noneMatch(sql -> sql.startsWith("SET SESSION"));
        } else {
            verify(fixture.connection).rollback();
        }
    }

    @Test
    void laterCollectorsDoNotChangeStatusObservationTimesOrDiscardValidDeltas() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        AtomicLong wallTime = new AtomicLong(100_000);
        AtomicInteger read = new AtomicInteger();
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        when(clock.millis()).thenAnswer(ignored -> wallTime.get());
        fixture.results = sql -> sql.contains("FROM performance_schema.global_status")
                ? List.of(
                        MySqlJdbcFixture.row("name", "Uptime", "value", read.get() == 0 ? "100" : "200"),
                        MySqlJdbcFixture.row("name", "Connections", "value", read.get() == 0 ? "10" : "20"))
                : fixture.defaults(sql);
        fixture.beforeQuery = () -> {
            if (read.get() == 1
                    && fixture.sql.get(fixture.sql.size() - 1).contains("FROM performance_schema.threads")) {
                wallTime.set(206_000);
            }
        };
        var service = MySqlInsightService.using(() -> inventory(fixture), null, clock);
        assertThat(service.read().dataSources().get(0).changes()).isEmpty();
        read.set(1);
        wallTime.set(200_000);
        var report = service.read();
        assertThat(report.readAt()).isEqualTo(206_000);
        assertThat(report.dataSources().get(0).changes()).singleElement().satisfies(change -> {
            assertThat(change.metric()).isEqualTo("Connections");
            assertThat(change.delta()).isEqualTo("10");
            assertThat(change.previousReadAt()).isEqualTo(100_000);
            assertThat(change.readAt()).isEqualTo(200_000);
        });
    }

    @Test
    void restorationFailureAbortsUnwrappedPhysicalConnectionBeforeReleasingPoolHandle() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        java.sql.Connection physical = org.mockito.Mockito.mock(java.sql.Connection.class);
        fixture.failedRestore = "SET SESSION max_execution_time=99";
        when(fixture.connection.unwrap(java.sql.Connection.class)).thenReturn(physical);
        // A pool close may report the already-aborted delegate while flushing/releasing its slot.
        org.mockito.Mockito.doThrow(new java.sql.SQLException("already closed", "08003"))
                .when(fixture.connection)
                .close();
        var report = service(fixture).read();
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("aborted and discarded"));
        var order = org.mockito.Mockito.inOrder(fixture.connection, physical);
        order.verify(fixture.connection).unwrap(java.sql.Connection.class);
        order.verify(physical).abort(any());
        order.verify(fixture.connection).close();
        // Agroal's logical abort can invalidate its delegate without releasing the pool slot.
        verify(fixture.connection, never()).abort(any());
    }

    @Test
    void failedPhysicalAbortQuarantinesWithoutCallingTheBrokenLogicalAbortOrClose() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        java.sql.Connection physical = org.mockito.Mockito.mock(java.sql.Connection.class);
        fixture.failedRestore = "SET SESSION max_execution_time=99";
        when(fixture.connection.unwrap(java.sql.Connection.class)).thenReturn(physical);
        org.mockito.Mockito.doThrow(new java.sql.SQLException("physical abort failed"))
                .when(physical)
                .abort(any());
        var service = service(fixture);
        assertThat(service.read().diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("quarantined"));
        verify(physical).abort(any());
        verify(fixture.connection, never()).abort(any());
        verify(fixture.connection, never()).close();
        assertThat(service.read().status()).isEqualTo("DISABLED");
    }

    @Test
    void unsupportedUnwrapRetainsTheDirectJdbcAbortFallback() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.failedRestore = "SET SESSION max_execution_time=99";
        when(fixture.connection.unwrap(java.sql.Connection.class))
                .thenThrow(new java.sql.SQLFeatureNotSupportedException());
        assertThat(service(fixture).read().diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("aborted and discarded"));
        verify(fixture.connection).abort(any());
        verify(fixture.connection).close();
    }

    @Test
    void nullDiscoveryIsAnExplicitProviderFailureNotAnAbsentDatasource() {
        MySqlInsightService service = MySqlInsightService.using(() -> null, null, Clock.systemUTC());
        var report = service.read();
        assertThat(report.status()).isEqualTo("ERROR");
        assertThat(report.dataSourcesRead()).isZero();
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("datasources");
            assertThat(diagnostic.level()).isEqualTo("ERROR");
            assertThat(diagnostic.message()).contains("discovery failed");
        });
    }

    @Test
    void budgetPartialRowsArePublishedAsPartialEvidenceRatherThanAHealthyEmptySection() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        AtomicLong time = new AtomicLong();
        fixture.duringMaterialization = query -> {
            if (query.contains("FROM performance_schema.global_status")) {
                time.set(11_000_000);
            }
        };
        MySqlInsightService service = new MySqlInsightService(
                () -> inventory(fixture),
                null,
                Clock.systemUTC(),
                MySqlRowLimits.defaults(),
                time::get,
                Duration.ofMillis(10));
        var report = service.read();
        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.truncated()).isFalse();
        assertThat(report.dataSources().get(0).vitalSigns()).hasSize(1);
        assertThat(report.dataSources().get(0).sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("vital-signs");
            assertThat(section.status()).isEqualTo("AVAILABLE");
            assertThat(section.rowCount()).isEqualTo(1);
            assertThat(section.reason()).contains("budget exhausted while consuming");
            assertThat(section.truncated()).isFalse();
        });
    }

    @Test
    void budgetBeforeAnyStatusRowMakesThatSectionFailedNotAvailableEmpty() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        AtomicLong time = new AtomicLong();
        fixture.beforeQuery = () -> {
            if (fixture.sql.get(fixture.sql.size() - 1).contains("FROM performance_schema.global_status")) {
                time.set(11_000_000);
            }
        };
        MySqlInsightService service = new MySqlInsightService(
                () -> inventory(fixture),
                null,
                Clock.systemUTC(),
                MySqlRowLimits.defaults(),
                time::get,
                Duration.ofMillis(10));
        var report = service.read();
        assertThat(report.status()).isEqualTo("ERROR");
        assertThat(report.dataSources().get(0).sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("vital-signs");
            assertThat(section.status()).isEqualTo("FAILED");
            assertThat(section.rowCount()).isZero();
            assertThat(section.reason()).contains("budget exhausted while consuming");
        });
    }

    @Test
    void cachedReadsAndConstructionDoNotEvenDiscoverDatasources() {
        AtomicInteger calls = new AtomicInteger();
        MySqlInsightService service = MySqlInsightService.using(
                () -> {
                    calls.incrementAndGet();
                    return new DatabaseAdvisorDataSourceDiscovery(List.of(), List.of());
                },
                null,
                Clock.systemUTC());
        assertThat(service.initialReport().status()).isEqualTo("NOT_READ");
        assertThat(service.report()).isSameAs(service.initialReport());
        assertThat(calls).hasValue(0);
        assertThat(service.read().status()).isEqualTo("DISABLED");
        assertThat(calls).hasValue(1);
    }

    @Test
    void manualCommitRefusalPrecedesMetadataSqlAndNeverTouchesTransaction() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.autoCommit = false;
        assertThat(service(fixture).read().status()).isEqualTo("ERROR");
        verify(fixture.connection, never()).getMetaData();
        verify(fixture.connection, never()).prepareStatement(anyString());
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).commit();
        verify(fixture.connection).close();
    }

    @Test
    void budgetIsRecheckedAfterPoolAcquisition() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        AtomicLong time = new AtomicLong();
        fixture.acquired = () -> time.set(Duration.ofSeconds(20).toNanos());
        MySqlInsightService service = new MySqlInsightService(
                () -> inventory(fixture),
                null,
                Clock.systemUTC(),
                MySqlRowLimits.defaults(),
                time::get,
                Duration.ofSeconds(15));
        assertThat(service.read().status()).isEqualTo("ERROR");
        verify(fixture.connection, never()).getMetaData();
        verify(fixture.connection, never()).prepareStatement(anyString());
    }

    @Test
    void publicationIsInsideSingleFlightAndExposureChangeDiscardsInflightRead() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ValueExposure> exposure = new AtomicReference<>(ValueExposure.FULL);
        fixture.acquired = () -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("test coordination timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        };
        MySqlInsightService service =
                MySqlInsightService.using(() -> inventory(fixture), policy(exposure), Clock.systemUTC());
        CompletableFuture<?> read = CompletableFuture.supplyAsync(service::read);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.report().status()).isEqualTo("NOT_READ");
            assertThatThrownBy(service::read).isInstanceOf(ActionBusyException.class);
            exposure.set(ValueExposure.METADATA_ONLY);
            assertThat(service.report().message()).contains("invalidated");
        } finally {
            release.countDown();
        }
        read.get(10, TimeUnit.SECONDS);
        assertThat(service.report().status()).isEqualTo("NOT_READ");
        assertThat(service.report().dataSources()).isEmpty();
        assertThat(service.read()).isSameAs(service.report());
    }

    @Test
    void tighteningAndRelaxingPolicyInvalidatesCachedReadWithoutSql() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        AtomicReference<ValueExposure> exposure = new AtomicReference<>(ValueExposure.FULL);
        MySqlInsightService service =
                MySqlInsightService.using(() -> inventory(fixture), policy(exposure), Clock.systemUTC());
        service.read();
        int queries = fixture.sql.size();
        exposure.set(ValueExposure.METADATA_ONLY);
        assertThat(service.report().status()).isEqualTo("NOT_READ");
        exposure.set(ValueExposure.FULL);
        assertThat(service.report().status()).isEqualTo("NOT_READ");
        assertThat(fixture.sql).hasSize(queries);
    }

    @Test
    void partialSourcesRetainRowsAndRestoreAllChangedState() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.deniedSource = "innodb_metrics";
        var report = service(fixture).read();
        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.dataSourcesRead()).isEqualTo(1);
        assertThat(report.dataSources().get(0).vitalSigns()).isNotEmpty();
        assertThat(report.dataSources().get(0).sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("innodb");
            assertThat(section.reason()).contains("cannot read");
        });
        assertThat(fixture.sql)
                .contains(
                        "SET SESSION transaction_read_only=1",
                        "START TRANSACTION READ ONLY",
                        "SET SESSION max_execution_time=99",
                        "SET SESSION lock_wait_timeout=88",
                        "SET SESSION transaction_read_only=0");
        assertThat(fixture.autoCommit).isTrue();
        assertThat(fixture.networkTimeout).isEqualTo(12000);
        verify(fixture.connection).rollback();
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).setReadOnly(true);
        assertThat(report.toString()).doesNotContain("unsafe credential");
        assertThat(fixture.sql).noneMatch(sql -> {
            String value = sql.toUpperCase(java.util.Locale.ROOT);
            return List.of("LOCK_DATA", "QUERY_SAMPLE_TEXT", "PROCESSLIST_INFO", "TRX_QUERY").stream()
                    .anyMatch(value::contains);
        });
    }

    @Test
    void restorationFailureAbortsAndFailedAbortQuarantinesRatherThanReturningToPool() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.failedRestore = "SET SESSION max_execution_time=99";
        fixture.abortFails = true;
        MySqlInsightService service = service(fixture);
        assertThat(service.read().diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("quarantined"));
        verify(fixture.connection).abort(any());
        verify(fixture.connection, never()).close();
        int calls = fixture.sql.size();
        assertThat(service.read().status()).isEqualTo("DISABLED");
        assertThat(fixture.sql).hasSize(calls);
    }

    @Test
    void unsuccessfulReadOnlyEnforcementFailsClosed() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql ->
                sql.contains("AS enforced") ? List.of(MySqlJdbcFixture.row("enforced", "0")) : fixture.defaults(sql);
        assertThat(service(fixture).read().status()).isEqualTo("ERROR");
        assertThat(fixture.sql).noneMatch(sql -> sql.contains("performance_schema."));
        verify(fixture.connection).rollback();
    }

    @Test
    void unsupportedAndMariaDbServersAreNotClaimedSupported() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        when(fixture.connection.getMetaData().getDatabaseProductName()).thenReturn("MariaDB");
        assertThat(service(fixture).read().status()).isEqualTo("DISABLED");
        assertThat(fixture.sql).isEmpty();
    }

    static MySqlInsightService service(MySqlJdbcFixture fixture) {
        return MySqlInsightService.using(() -> inventory(fixture), null, Clock.systemUTC());
    }

    static DatabaseAdvisorDataSourceDiscovery inventory(MySqlJdbcFixture fixture) {
        return new DatabaseAdvisorDataSourceDiscovery(
                List.of(new NamedDataSource("fixture", fixture.source)), List.of());
    }

    private static ExposurePolicy policy(AtomicReference<ValueExposure> exposure) {
        return new ExposurePolicy() {
            public ValueExposure valueExposure() {
                return exposure.get();
            }

            public boolean maskSecrets() {
                return true;
            }
        };
    }
}
