package io.github.jdubois.bootui.engine.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MySqlCollectorsTests {
    @Test
    void coordinatorOnlyErrorsSurviveTheChannelProjection() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql -> {
            if (sql.contains("FROM performance_schema.replication_connection_status")) {
                return List.of(MySqlJdbcFixture.row("channel", "fixture", "state", "ON", "error", "0"));
            }
            if (sql.contains("FROM performance_schema.replication_applier_status_by_coordinator")) {
                return List.of(MySqlJdbcFixture.row("channel", "fixture", "error", "1756"));
            }
            if (sql.contains("FROM performance_schema.replication_applier_status_by_worker")) {
                return List.of(MySqlJdbcFixture.row("channel", "fixture", "workers", "4", "errors", "0", "error", "0"));
            }
            if (sql.contains("FROM performance_schema.replication_applier_status ORDER")) {
                return List.of(MySqlJdbcFixture.row("channel", "fixture", "state", "OFF"));
            }
            return fixture.defaults(sql);
        };
        var source =
                MySqlInsightServiceTests.service(fixture).read().dataSources().get(0);
        assertThat(source.replication()).singleElement().satisfies(channel -> {
            assertThat(channel.applierState()).isEqualTo("OFF");
            assertThat(channel.lastErrorNumber()).isEqualTo(1756);
            assertThat(channel.errorCount()).isEqualTo("0");
        });
    }

    @Test
    void deniedCoordinatorEvidenceIsPartialAndDoesNotCertifyZeroErrors() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.deniedSource = "FROM performance_schema.replication_applier_status_by_coordinator";
        fixture.results = sql -> sql.contains("FROM performance_schema.replication_connection_status")
                ? List.of(MySqlJdbcFixture.row("channel", "fixture", "state", "ON", "error", "0"))
                : fixture.defaults(sql);
        var source =
                MySqlInsightServiceTests.service(fixture).read().dataSources().get(0);
        assertThat(source.replication()).singleElement().satisfies(channel -> {
            assertThat(channel.receiverState()).isEqualTo("ON");
            assertThat(channel.lastErrorNumber()).isNull();
        });
        assertThat(source.sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("replication");
            assertThat(section.status()).isEqualTo("AVAILABLE");
            assertThat(section.reason()).contains("replication_applier_status_by_coordinator", "cannot read");
        });
    }

    @Test
    void globalDigestCollectionDoesNotDependOnThreadInstrumentation() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql -> sql.contains("setup_consumers")
                ? List.of(
                        MySqlJdbcFixture.row("name", "global_instrumentation", "enabled", "YES"),
                        MySqlJdbcFixture.row("name", "statements_digest", "enabled", "YES"),
                        MySqlJdbcFixture.row("name", "thread_instrumentation", "enabled", "NO"))
                : fixture.defaults(sql);
        var source =
                MySqlInsightServiceTests.service(fixture).read().dataSources().get(0);
        assertThat(source.sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("statements");
            assertThat(section.status()).isEqualTo("AVAILABLE");
        });
        assertThat(fixture.sql)
                .anyMatch(sql -> sql.contains("FROM performance_schema.events_statements_summary_by_digest"));
    }

    @Test
    void metricLabelsExplainTheObservationWithoutChangingIdsValuesUnitsOrScopes() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql -> sql.contains("FROM performance_schema.global_status")
                ? List.of(
                        MySqlJdbcFixture.row("name", "Uptime", "value", "100"),
                        MySqlJdbcFixture.row("name", "Threads_running", "value", "2"),
                        MySqlJdbcFixture.row("name", "Connections", "value", "9007199254740993"),
                        MySqlJdbcFixture.row("name", "Innodb_row_lock_time", "value", "0"))
                : fixture.defaults(sql);
        var source =
                MySqlInsightServiceTests.service(fixture).read().dataSources().get(0);
        assertThat(source.vitalSigns()).anySatisfy(metric -> {
            assertThat(metric.id()).isEqualTo("Connections");
            assertThat(metric.label()).isEqualTo("Connection attempts");
            assertThat(metric.value()).isEqualTo("9007199254740993");
            assertThat(metric.unit()).isEqualTo("count");
            assertThat(metric.scope()).isEqualTo("SERVER");
        });
        assertThat(source.vitalSigns()).anySatisfy(metric -> {
            assertThat(metric.id()).isEqualTo("Threads_running");
            assertThat(metric.label()).isEqualTo("Non-sleeping threads");
        });
        assertThat(source.innodb()).anySatisfy(metric -> {
            assertThat(metric.id()).isEqualTo("Innodb_row_lock_time");
            assertThat(metric.label()).isEqualTo("Cumulative row-lock wait time");
            assertThat(metric.value()).isEqualTo("0");
            assertThat(metric.unit()).isEqualTo("milliseconds");
        });
        assertThat(source.innodb()).anySatisfy(metric -> {
            assertThat(metric.id()).isEqualTo("trx_rseg_history_len");
            assertThat(metric.label()).isEqualTo("InnoDB history-list length");
        });
    }

    @Test
    void noSelectedSchemaKeepsServerEvidenceAndSkipsOnlyAssociatedCollectors() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql -> {
            if (sql.contains("@@version AS version")) {
                Map<String, String> row =
                        new java.util.LinkedHashMap<>(fixture.defaults(sql).get(0));
                row.put("schema_name", null);
                return List.of(row);
            }
            return fixture.defaults(sql);
        };
        var source =
                MySqlInsightServiceTests.service(fixture).read().dataSources().get(0);
        assertThat(source.schemaName()).isNull();
        assertThat(source.vitalSigns()).isNotEmpty();
        assertThat(source.sections())
                .filteredOn(section ->
                        List.of("sessions", "statements", "tables", "indexes").contains(section.id()))
                .allSatisfy(section -> {
                    assertThat(section.status()).isEqualTo("SKIPPED");
                    assertThat(section.reason()).contains("No selected schema");
                });
        assertThat(fixture.sql).noneMatch(sql -> sql.contains("FROM performance_schema.threads"));
    }

    @Test
    void serverOverflowIsNotAttributedToSchemaAndDoesNotSetBootuiTruncation() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql -> sql.contains("WHERE SCHEMA_NAME IS NULL AND DIGEST IS NULL")
                ? List.of(MySqlJdbcFixture.row("calls", "9007199254740993"))
                : fixture.defaults(sql);
        var report = MySqlInsightServiceTests.service(fixture).read();
        assertThat(report.truncated()).isFalse();
        assertThat(report.dataSources().get(0).sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("statements");
            assertThat(section.reason()).contains("server-wide", "separate from BootUI row caps");
        });
        assertThat(report.dataSources().get(0).capabilities()).anySatisfy(capability -> {
            assertThat(capability.id()).isEqualTo("digest-overflow");
            assertThat(capability.scope()).isEqualTo("SERVER");
        });
    }

    @Test
    void deniedAppliersKeepIndependentReceiverAndWorkerObservations() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.deniedSource = "FROM performance_schema.replication_applier_status ORDER";
        fixture.results = sql -> {
            if (sql.contains("FROM performance_schema.replication_connection_status")) {
                return List.of(MySqlJdbcFixture.row("channel", "fixture", "state", "ON", "error", "0"));
            }
            if (sql.contains("FROM performance_schema.replication_applier_status_by_worker")) {
                return List.of(MySqlJdbcFixture.row(
                        "channel", "fixture", "workers", "9007199254740993", "errors", "2", "error", "1062"));
            }
            return fixture.defaults(sql);
        };
        var source =
                MySqlInsightServiceTests.service(fixture).read().dataSources().get(0);
        assertThat(source.replication()).singleElement().satisfies(channel -> {
            assertThat(channel.receiverState()).isEqualTo("ON");
            assertThat(channel.applierState()).isNull();
            assertThat(channel.workerCount()).isEqualTo("9007199254740993");
            assertThat(channel.errorCount()).isEqualTo("2");
            assertThat(channel.lastErrorNumber()).isEqualTo(1062);
        });
        assertThat(source.sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("replication");
            assertThat(section.status()).isEqualTo("AVAILABLE");
            assertThat(section.reason()).contains("cannot read");
        });
    }

    @Test
    void configuredLockCapBoundsCombinedRowEdgesAndPendingMetadata() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql -> {
            if (sql.contains("FROM (SELECT w.ENGINE")) {
                return List.of(MySqlJdbcFixture.row("requesting_thread", "10", "blocking_thread", "20"));
            }
            if (sql.contains("FROM performance_schema.metadata_locks")) {
                return List.of(MySqlJdbcFixture.row("requesting_thread", "11"));
            }
            return fixture.defaults(sql);
        };
        var service = MySqlInsightService.using(
                () -> MySqlInsightServiceTests.inventory(fixture),
                null,
                java.time.Clock.systemUTC(),
                new MySqlRowLimits(10, 10, 10, 10, 1, 10, 40));
        var report = service.read();
        assertThat(report.truncated()).isTrue();
        assertThat(report.dataSources().get(0).lockWaits()).hasSize(1);
    }

    @Test
    void unboundedSourceInventoryAndDiscoveryFailuresStayBoundedAndSafe() throws Exception {
        List<io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery.Failure> failures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            failures.add(new io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery.Failure(
                    "source-" + i, "password=unsafe-unbounded-content"));
        }
        var service = MySqlInsightService.using(
                () -> new io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery(List.of(), failures),
                null,
                java.time.Clock.systemUTC());
        var report = service.read();
        assertThat(report.diagnostics()).hasSize(32);
        assertThat(report.toString()).doesNotContain("unsafe-unbounded-content");
        assertThat(report.status()).isEqualTo("ERROR");
    }
}
