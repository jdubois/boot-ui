package io.github.jdubois.bootui.engine.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MySqlInstrumentationTests {

    @ParameterizedTest
    @ValueSource(
            strings = {"global-off", "handler-off", "handler-untimed", "object-off", "object-untimed", "objects-denied"
            })
    void unavailableTableInstrumentationNeverLooksLikeZeroActivityOrLatency(String scenario) throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        if (scenario.equals("objects-denied")) {
            fixture.deniedSource = "FROM performance_schema.setup_objects";
        }
        fixture.results = sql -> {
            if (sql.contains("setup_consumers") && scenario.equals("global-off")) {
                return List.of(
                        MySqlJdbcFixture.row("name", "global_instrumentation", "enabled", "NO"),
                        MySqlJdbcFixture.row("name", "statements_digest", "enabled", "YES"),
                        MySqlJdbcFixture.row("name", "thread_instrumentation", "enabled", "YES"));
            }
            if (sql.contains("setup_instruments")) {
                return instruments(
                        scenario.equals("handler-off") ? "0" : "1",
                        scenario.equals("handler-off") || scenario.equals("handler-untimed") ? "0" : "1",
                        "1");
            }
            if (sql.contains("setup_objects") && scenario.startsWith("object-")) {
                return List.of(MySqlJdbcFixture.row(
                        "schema_name",
                        "fixture",
                        "object_name",
                        "orders",
                        "enabled",
                        scenario.equals("object-off") ? "NO" : "YES",
                        "timed",
                        "NO"));
            }
            return tableRows(fixture, sql);
        };
        var source =
                MySqlInsightServiceTests.service(fixture).read().dataSources().get(0);
        boolean untimed = scenario.endsWith("untimed");
        assertThat(source.tables()).singleElement().satisfies(table -> {
            assertThat(table.tableName()).isEqualTo("orders");
            assertThat(table.dataBytes()).isEqualTo("128");
            assertThat(table.readOperations()).isEqualTo(untimed ? "0" : null);
            assertThat(table.writeOperations()).isEqualTo(untimed ? "2" : null);
            assertThat(table.totalTimeMs()).isNull();
        });
        assertThat(source.indexes()).singleElement().satisfies(index -> {
            assertThat(index.indexName()).isEqualTo("PRIMARY");
            assertThat(index.readOperations()).isEqualTo(untimed ? "0" : null);
            assertThat(index.writeOperations()).isEqualTo(untimed ? "2" : null);
            assertThat(index.totalTimeMs()).isNull();
        });
        assertThat(source.sections())
                .filteredOn(section -> List.of("tables", "indexes").contains(section.id()))
                .allSatisfy(section -> {
                    assertThat(section.status()).isEqualTo("AVAILABLE");
                    assertThat(section.reason()).contains(untimed ? "Table I/O timing" : "Table I/O collection");
                    if (scenario.equals("objects-denied")) {
                        assertThat(section.reason()).contains("setup_objects", "cannot read");
                    }
                });
        assertThat(source.capabilities())
                .filteredOn(capability -> capability.source().contains("table_io_waits_summary"))
                .allSatisfy(capability -> {
                    assertThat(capability.timing()).isNotEqualTo("ENABLED");
                    assertThat(capability.collection())
                            .isEqualTo(
                                    untimed ? "ENABLED" : scenario.equals("objects-denied") ? "UNKNOWN" : "DISABLED");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"instrument-off", "probe-denied", "global-off", "thread-off"})
    void metadataLockDependenciesAreQualifiedWithoutDiscardingSessionsOrRowLocks(String scenario) throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        boolean denied = scenario.equals("probe-denied");
        if (denied) {
            fixture.deniedSource = "FROM performance_schema.setup_instruments";
        }
        fixture.results = sql -> {
            if (sql.contains("setup_consumers")) {
                return List.of(
                        MySqlJdbcFixture.row(
                                "name",
                                "global_instrumentation",
                                "enabled",
                                scenario.equals("global-off") ? "NO" : "YES"),
                        MySqlJdbcFixture.row("name", "statements_digest", "enabled", "YES"),
                        MySqlJdbcFixture.row(
                                "name",
                                "thread_instrumentation",
                                "enabled",
                                scenario.equals("thread-off") ? "NO" : "YES"));
            }
            if (sql.contains("setup_instruments")) {
                return instruments("1", "1", scenario.equals("instrument-off") ? "0" : "1");
            }
            if (sql.contains("FROM performance_schema.threads")) {
                return List.of(MySqlJdbcFixture.row("thread_id", "10", "connection_id", "11", "command", "Sleep"));
            }
            if (sql.contains("FROM (SELECT w.ENGINE")) {
                return List.of(MySqlJdbcFixture.row("requesting_thread", "10", "blocking_thread", "20"));
            }
            return fixture.defaults(sql);
        };
        var source =
                MySqlInsightServiceTests.service(fixture).read().dataSources().get(0);
        String expected = denied ? "UNKNOWN" : scenario.equals("thread-off") ? "ENABLED" : "DISABLED";
        assertThat(source.sessions()).hasSize(1);
        assertThat(source.lockWaits()).hasSize(1);
        assertThat(source.sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("sessions");
            assertThat(section.status()).isEqualTo("AVAILABLE");
            if (expected.equals("ENABLED")) {
                assertThat(section.reason()).isNull();
            } else {
                assertThat(section.reason())
                        .contains("Metadata-lock instrumentation is " + expected.toLowerCase(java.util.Locale.ROOT));
            }
        });
        assertThat(source.capabilities()).anySatisfy(capability -> {
            assertThat(capability.id()).isEqualTo("performance_schema.metadata_locks");
            assertThat(capability.collection()).isEqualTo(expected);
        });
    }

    @Test
    void serverNormalizedKeysJoinCatalogAndIoEvidenceUnderFoldedIdentifierMode() throws Exception {
        MySqlJdbcFixture fixture = new MySqlJdbcFixture();
        fixture.results = sql -> {
            if (sql.contains("@@version AS version")) {
                var identity =
                        new java.util.LinkedHashMap<>(fixture.defaults(sql).get(0));
                identity.put("schema_name", "FiXtUrE");
                identity.put("lower_case_table_names", "2");
                return List.of(identity);
            }
            if (sql.contains(" AS instrumentation_schema")) {
                return List.of(MySqlJdbcFixture.row("instrumentation_schema", "fixture"));
            }
            if (sql.contains("FROM information_schema.tables")) {
                return List.of(MySqlJdbcFixture.row(
                        "schema_name",
                        "FiXtUrE",
                        "table_name",
                        "Orders",
                        "instrumentation_name",
                        "orders",
                        "engine",
                        "InnoDB",
                        "estimated_rows",
                        "3",
                        "data_bytes",
                        "128"));
            }
            return tableRows(fixture, sql);
        };
        var source =
                MySqlInsightServiceTests.service(fixture).read().dataSources().get(0);
        assertThat(source.tables()).singleElement().satisfies(table -> {
            assertThat(table.tableName()).isEqualTo("Orders");
            assertThat(table.readOperations()).isEqualTo("0");
            assertThat(table.writeOperations()).isEqualTo("2");
        });
        assertThat(fixture.sql).anyMatch(sql -> sql.contains("LOWER(CONVERT(TABLE_NAME USING utf8mb4)"));
    }

    private static List<Map<String, String>> instruments(String enabled, String timed, String metadata) {
        return List.of(
                MySqlJdbcFixture.row("category", "statement", "instruments", "1", "enabled", "1", "timed", "1"),
                MySqlJdbcFixture.row("category", "table", "instruments", "1", "enabled", enabled, "timed", timed),
                MySqlJdbcFixture.row(
                        "category", "metadata-lock", "instruments", "1", "enabled", metadata, "timed", metadata));
    }

    private static List<Map<String, String>> tableRows(MySqlJdbcFixture fixture, String sql) {
        if (sql.contains("FROM information_schema.tables")) {
            return List.of(MySqlJdbcFixture.row(
                    "schema_name",
                    "fixture",
                    "table_name",
                    "orders",
                    "instrumentation_name",
                    "orders",
                    "engine",
                    "InnoDB",
                    "estimated_rows",
                    "3",
                    "data_bytes",
                    "128",
                    "index_bytes",
                    "64"));
        }
        if (sql.contains("FROM performance_schema.table_io_waits_summary")) {
            return List.of(MySqlJdbcFixture.row(
                    "schema_name",
                    "fixture",
                    "table_name",
                    "orders",
                    "instrumentation_name",
                    "orders",
                    "index_name",
                    "PRIMARY",
                    "read_ops",
                    "0",
                    "write_ops",
                    "2",
                    "time",
                    "0"));
        }
        return fixture.defaults(sql);
    }
}
