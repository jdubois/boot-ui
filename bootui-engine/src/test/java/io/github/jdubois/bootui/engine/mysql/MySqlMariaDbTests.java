package io.github.jdubois.bootui.engine.mysql;

import static io.github.jdubois.bootui.engine.mysql.MySqlInsightServiceTests.service;
import static io.github.jdubois.bootui.engine.mysql.MySqlJdbcFixture.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** MariaDB reached through MySQL Connector/J: read on a best-effort basis, labelled unsupported. */
class MySqlMariaDbTests {
    private static final String VERSION = "11.4.13-MariaDB-ubu2404";

    @Test
    void mariaDbIsReadWithItsOwnSessionGuardsAndLabelledUnsupported() throws Exception {
        MySqlJdbcFixture fixture = mariaDb(VERSION);
        var report = service(fixture).read();

        // The fixture's two status rows are deliberately incomplete, so the read is PARTIAL rather than READ.
        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.dataSources()).singleElement().satisfies(source -> {
            assertThat(source.serverFlavor()).isEqualTo("MARIADB");
            assertThat(source.serverVersion()).isEqualTo(VERSION);
            assertThat(source.sections())
                    .allSatisfy(section -> assertThat(section.status()).isEqualTo("AVAILABLE"));
        });
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo("INFO");
            assertThat(diagnostic.message())
                    .startsWith("MariaDB " + VERSION + " is not supported.")
                    .contains("receiver state", "counter changes");
        });
        assertThat(fixture.sql)
                .contains(
                        "SET SESSION max_statement_time=5.000",
                        "SET SESSION lock_wait_timeout=2",
                        "SET SESSION transaction_read_only=1",
                        "START TRANSACTION READ ONLY",
                        "SET SESSION max_statement_time=1.5",
                        "SET SESSION lock_wait_timeout=88",
                        "SET SESSION transaction_read_only=0")
                .noneMatch(sql -> sql.contains("max_execution_time") || sql.contains("MAX_EXECUTION_TIME"))
                .noneMatch(sql -> sql.contains("@@server_uuid") || sql.contains("utf8mb4_0900_bin"))
                .noneMatch(sql -> sql.contains("performance_schema.data_lock")
                        || sql.contains("replication_connection_status")
                        || sql.contains("super_read_only")
                        || sql.contains("information_schema_stats_expiry"));
        assertThat(fixture.sql)
                .as("the captured session timeout must be the session's, not a SET STATEMENT override")
                .contains("SELECT @@session.max_statement_time AS select_timeout,"
                        + " @@session.lock_wait_timeout AS lock_timeout,"
                        + " @@session.transaction_read_only AS transaction_read_only LIMIT ?");
        assertThat(fixture.sql.stream().filter(sql -> sql.contains("SELECT ") && !sql.contains(" AS select_timeout")))
                .isNotEmpty()
                .allMatch(sql -> sql.startsWith("/*M!100102 SET STATEMENT max_statement_time=")
                        && sql.contains(" FOR */ SELECT "));
        assertThat(fixture.sql)
                .anyMatch(sql -> sql.contains("ENABLED AS status FROM information_schema.innodb_metrics"));
        verify(fixture.connection).rollback();
    }

    @Test
    void mariaDbSettingsInnodbCountersAndNoCounterChanges() throws Exception {
        MySqlJdbcFixture fixture = mariaDb(VERSION);
        fixture.results = withMariaDbDefaults(fixture, sql -> {
            if (sql.contains("@@global.max_connections")) {
                return MySqlCollectors.MARIADB_SETTINGS.stream()
                        .map(name -> row("name", name, "value", "1"))
                        .toList();
            }
            return null;
        });
        MySqlInsightService service = service(fixture);
        service.read();
        var source = service.read().dataSources().get(0);

        assertThat(MySqlCollectors.MARIADB_SETTINGS)
                .hasSize(MySqlCollectors.SETTINGS.size() - 2)
                .doesNotContain("super_read_only", "information_schema_stats_expiry");
        assertThat(source.settings()).hasSize(MySqlCollectors.MARIADB_SETTINGS.size());
        assertThat(source.innodb())
                .filteredOn(metric -> metric.source().equals("information_schema.innodb_metrics"))
                .hasSize(2)
                .allSatisfy(metric -> assertThat(metric.value()).isEqualTo("0"));
        assertThat(source.vitalSigns()).isNotEmpty();
        assertThat(source.changes())
                .as("MariaDB has no server UUID, so no baseline may be kept")
                .isEmpty();
    }

    @Test
    void rowLockWaitsComeFromInnodbLockViewsMappedToPerformanceSchemaThreads() throws Exception {
        MySqlJdbcFixture fixture = mariaDb(VERSION);
        fixture.results = withMariaDbDefaults(
                fixture,
                sql -> sql.contains("information_schema.innodb_lock_waits")
                        ? List.of(row(
                                "requesting_thread",
                                "16",
                                "blocking_thread",
                                "15",
                                "lock_table",
                                "`fixture`.`we``ird`",
                                "index_name",
                                "PRIMARY",
                                "requested_mode",
                                "X",
                                "blocking_mode",
                                "X"))
                        : null);
        var source = service(fixture).read().dataSources().get(0);

        assertThat(source.lockWaits()).singleElement().satisfies(wait -> {
            assertThat(wait.kind()).isEqualTo("ROW");
            assertThat(wait.requestingThreadId()).isEqualTo("16");
            assertThat(wait.blockingThreadId()).isEqualTo("15");
            assertThat(wait.schemaName()).isEqualTo("fixture");
            assertThat(wait.objectName()).isEqualTo("we`ird");
            assertThat(wait.indexName()).isEqualTo("PRIMARY");
        });
        assertThat(source.capabilities()).anySatisfy(capability -> {
            assertThat(capability.id()).isEqualTo("information_schema.innodb_lock_waits");
            assertThat(capability.scope()).isEqualTo(MySqlCollectors.SCHEMA);
        });
        List<String> prepared =
                fixture.sql.stream().filter(sql -> sql.contains("SELECT ")).toList();
        int index = prepared.indexOf(prepared.stream()
                .filter(sql -> sql.contains("information_schema.innodb_lock_waits"))
                .findFirst()
                .orElseThrow());
        assertThat(prepared.get(index)).doesNotContain("LOCK_DATA");
        verify(fixture.prepared.get(index)).setObject(1, 10);
        verify(fixture.prepared.get(index)).setObject(2, "`fixture`.");
    }

    @Test
    void replicationChannelsOnMariaDbKeepReceiverStateUnknown() throws Exception {
        MySqlJdbcFixture fixture = mariaDb(VERSION);
        fixture.results = withMariaDbDefaults(fixture, sql -> {
            if (sql.contains("FROM performance_schema.replication_applier_status ")) {
                return List.of(row("channel", "", "state", "ON"));
            }
            if (sql.contains("replication_applier_status_by_coordinator")) {
                return List.of(row("channel", "", "error", "0"));
            }
            return null;
        });
        var source = service(fixture).read().dataSources().get(0);

        assertThat(source.replication()).singleElement().satisfies(channel -> {
            assertThat(channel.applierState()).isEqualTo("ON");
            assertThat(channel.receiverState()).isNull();
            assertThat(channel.lastErrorNumber()).isNull();
        });
        assertThat(source.sections())
                .filteredOn(section -> section.id().equals("replication"))
                .singleElement()
                .satisfies(section -> assertThat(section.reason()).contains("receiver (I/O thread)"));
    }

    @Test
    void noReplicationChannelsLeaveTheMariaDbReadComplete() throws Exception {
        var source = service(mariaDb(VERSION)).read().dataSources().get(0);
        assertThat(source.sections())
                .filteredOn(section -> section.id().equals("replication"))
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.status()).isEqualTo("AVAILABLE");
                    assertThat(section.reason()).isNull();
                });
    }

    @Test
    void serverThatDoesNotConfirmMariaDbIsRefusedBeforeStatistics() throws Exception {
        MySqlJdbcFixture fixture = mariaDb(VERSION);
        fixture.results = withMariaDbDefaults(fixture, sql -> {
            if (!sql.contains("@@version AS version")) {
                return null;
            }
            Map<String, String> identity = new LinkedHashMap<>(identity(VERSION));
            identity.put("version", "8.4.6");
            return List.of(identity);
        });
        var report = service(fixture).read();

        assertThat(report.status()).isEqualTo("DISABLED");
        assertThat(report.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.message()).contains("neither an Oracle MySQL"));
        assertThat(fixture.sql).noneMatch(sql -> sql.contains("performance_schema."));
        verify(fixture.connection).rollback();
    }

    @Test
    void missingSessionGuardVariablesFailClosedWithAClearReason() throws Exception {
        MySqlJdbcFixture fixture = mariaDb("10.11.14-MariaDB");
        fixture.unknownVariableSource = " AS select_timeout";
        var report = service(fixture).read();

        assertThat(report.status()).isEqualTo("ERROR");
        assertThat(report.dataSources())
                .singleElement()
                .satisfies(
                        source -> assertThat(source.message()).contains("read-only guards could not be established"));
        assertThat(fixture.sql).noneMatch(sql -> sql.startsWith("SET SESSION"));
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection).close();
    }

    @Test
    void mariaDbRestorationFailureAbortsTheConnection() throws Exception {
        MySqlJdbcFixture fixture = mariaDb(VERSION);
        fixture.failedRestore = "SET SESSION max_statement_time=1.5";
        var report = service(fixture).read();

        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("aborted and discarded"));
        verify(fixture.connection).abort(any());
    }

    @Test
    void mariaDbConnectorJStaysOutOfScope() {
        assertThat(MySqlDataSourceDetection.isMySqlJdbcUrl("jdbc:mariadb://localhost/app"))
                .isFalse();
        assertThat(MySqlDataSourceDetection.isMySqlJdbcUrl("jdbc:mysql://localhost/app"))
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "NULL",
            value = {
                "`fixture`.`orders`|orders",
                "`fixture`.`we``ird`|we`ird",
                "`fixture`.`orders` /* Partition `p0` */|orders",
                "`fix``ture`.`orders`|orders",
                "fixture.orders|NULL",
                "`fixture`.`unterminated|NULL",
                "`fixture`|NULL",
                "NULL|NULL"
            })
    void lockedObjectUnquotesTheTableName(String lockTable, String expected) {
        assertThat(MySqlCollectors.lockedObject(lockTable)).isEqualTo(expected);
    }

    @Test
    void mariaDbStatementsAreBoundedInSeconds() {
        assertThat(MySqlQuery.seconds(5000)).isEqualTo("5.000");
        assertThat(MySqlQuery.seconds(1)).isEqualTo("0.001");
        assertThat(MySqlFlavor.mariaDb("11.8.9-MariaDB-ubu2404")).isTrue();
        assertThat(MySqlFlavor.mariaDb("8.4.6")).isFalse();
        assertThat(MySqlFlavor.mariaDb(null)).isFalse();
    }

    private static MySqlJdbcFixture mariaDb(String version) throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        when(fixture.connection.getMetaData().getDatabaseProductVersion()).thenReturn(version);
        fixture.results = withMariaDbDefaults(
                fixture, sql -> sql.contains("@@version AS version") ? List.of(identity(version)) : null);
        return fixture;
    }

    private static java.util.function.Function<String, List<Map<String, String>>> withMariaDbDefaults(
            MySqlJdbcFixture fixture, java.util.function.Function<String, List<Map<String, String>>> overrides) {
        return sql -> {
            List<Map<String, String>> override = overrides.apply(sql);
            if (override != null) {
                return override;
            }
            if (sql.contains(" AS select_timeout")) {
                return List.of(row("select_timeout", "1.5", "lock_timeout", "88", "transaction_read_only", "0"));
            }
            if (sql.contains("@@version AS version")) {
                return List.of(identity(VERSION));
            }
            if (sql.contains("innodb_metrics")) {
                return List.of(
                        row("name", "lock_deadlocks", "value", "0", "status", "1"),
                        row("name", "trx_rseg_history_len", "value", "0", "status", "1"));
            }
            return fixture.defaults(sql);
        };
    }

    private static Map<String, String> identity(String version) {
        return row(
                "version",
                version,
                "flavor",
                "mariadb.org binary distribution",
                "schema_name",
                "fixture",
                "account",
                "reader@%",
                "server_id",
                null,
                "connection_id",
                "9",
                "performance_schema",
                "1",
                "lower_case_table_names",
                "0");
    }
}
