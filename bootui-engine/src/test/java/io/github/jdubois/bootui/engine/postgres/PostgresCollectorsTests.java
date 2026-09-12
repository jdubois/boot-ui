package io.github.jdubois.bootui.engine.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgresCollectorsTests {

    @Test
    void statementCollectorUsesVersionSpecificTimingColumns() throws SQLException {
        var oldContext = PostgresTestDataSources.context(PostgresTestDataSources.postgres(), 12, exposure());
        var newContext = PostgresTestDataSources.context(PostgresTestDataSources.postgres(), 13, exposure());

        assertThat(PostgresStatementCollector.sql(oldContext))
                .contains("s.total_time as total_time", "s.mean_time as mean_time")
                .doesNotContain("total_exec_time");
        assertThat(PostgresStatementCollector.sql(newContext))
                .contains("s.total_exec_time as total_time", "s.mean_exec_time as mean_time");
    }

    @Test
    void replicationCollectorUsesVersionSpecificCheckpointViews() throws SQLException {
        assertThat(PostgresReplicationCollector.checkpointSql(
                        PostgresTestDataSources.context(PostgresTestDataSources.postgres(), 16, exposure())))
                .contains("pg_stat_bgwriter", "checkpoints_req")
                .doesNotContain("pg_stat_checkpointer");
        assertThat(PostgresReplicationCollector.checkpointSql(
                        PostgresTestDataSources.context(PostgresTestDataSources.postgres(), 17, exposure())))
                .contains("pg_stat_checkpointer", "num_requested");
    }

    @Test
    void listQueriesReadOnePastTheBoundAndReportTruncation() throws SQLException {
        var dataSource = PostgresTestDataSources.postgres()
                .rows(
                        PostgresTestDataSources.QueryKind.TABLES,
                        PostgresTestDataSources.row("schema_name", "public", "table_name", "orders"),
                        PostgresTestDataSources.row("schema_name", "public", "table_name", "customers"));
        var context = new PostgresReadContext(
                dataSource.getConnection(),
                io.github.jdubois.bootui.engine.databaseadvisor.DatabaseVersion.of(15, 0, "15.0"),
                PostgresReadBudget.of(java.time.Duration.ofSeconds(15), () -> 0),
                new PostgresInsightLimits(
                        25,
                        50,
                        1,
                        25,
                        10,
                        40,
                        400,
                        java.time.Duration.ofSeconds(15),
                        java.time.Duration.ofSeconds(5),
                        java.time.Duration.ofSeconds(2)),
                exposure());
        PostgresDatabaseData data = new PostgresDatabaseData("primary");

        PostgresSectionDto section = new PostgresTableCollector().collect(context, data);

        assertThat(section.status()).isEqualTo("AVAILABLE");
        assertThat(section.rowCount()).isEqualTo(1);
        assertThat(section.truncated()).isTrue();
        assertThat(data.tables()).extracting(table -> table.table()).containsExactly("orders");
    }

    @Test
    void missingPgStatStatementsExtensionIsSkippedWithActionableHint() throws SQLException {
        var dataSource = PostgresTestDataSources.postgres()
                .rows(PostgresTestDataSources.QueryKind.EXTENSION, PostgresTestDataSources.row("installed", 0));
        PostgresDatabaseData data = new PostgresDatabaseData("primary");

        PostgresSectionDto section = new PostgresStatementCollector()
                .collect(PostgresTestDataSources.context(dataSource, 15, exposure()), data);

        assertThat(section.status()).isEqualTo("SKIPPED");
        assertThat(section.reason()).contains("pg_stat_statements extension is not installed");
        assertThat(section.hint()).contains("CREATE EXTENSION pg_stat_statements");
        assertThat(data.statements()).isEmpty();
    }

    @Test
    void failedCollectorQueriesKeepCredentialRedactedReason() throws SQLException {
        var dataSource = PostgresTestDataSources.postgres()
                .fail(PostgresTestDataSources.QueryKind.INDEXES, "permission denied for jdbc:******db/app");
        PostgresDatabaseData data = new PostgresDatabaseData("primary");

        PostgresSectionDto section =
                new PostgresIndexCollector().collect(PostgresTestDataSources.context(dataSource, 15, exposure()), data);

        assertThat(section.status()).isEqualTo("FAILED");
        assertThat(section.reason())
                .contains("Index usage statistics could not be read")
                .contains("******db/app")
                .doesNotContain("secret");
        assertThat(data.indexes()).isEmpty();
    }

    @Test
    void collectorsPopulateDataAndReportPartialSubsystemsExplicitly() throws SQLException {
        long now = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        var dataSource = PostgresTestDataSources.postgres()
                .rows(
                        PostgresTestDataSources.QueryKind.STATEMENTS,
                        PostgresTestDataSources.row(
                                "query_id",
                                "42",
                                "query",
                                "select 'secret'",
                                "calls",
                                7L,
                                "total_time",
                                70d,
                                "mean_time",
                                10d,
                                "max_time",
                                20d,
                                "rows_returned",
                                7L,
                                "shared_blks_hit",
                                9L,
                                "shared_blks_read",
                                1L))
                .rows(
                        PostgresTestDataSources.QueryKind.INDEXES,
                        PostgresTestDataSources.row(
                                "schema_name",
                                "public",
                                "table_name",
                                "orders",
                                "index_name",
                                "orders_idx",
                                "scans",
                                1L,
                                "tuples_read",
                                2L,
                                "size_bytes",
                                3L,
                                "is_unique",
                                false,
                                "is_primary",
                                false,
                                "constraint_backed",
                                false))
                .rows(
                        PostgresTestDataSources.QueryKind.TABLES,
                        PostgresTestDataSources.row(
                                "schema_name",
                                "public",
                                "table_name",
                                "orders",
                                "total_size",
                                100L,
                                "table_size",
                                80L,
                                "index_size",
                                20L,
                                "live_tuples",
                                10L,
                                "dead_tuples",
                                1L,
                                "sequential_scans",
                                1L,
                                "sequential_tuples",
                                10L,
                                "index_scans",
                                9L))
                .rows(
                        PostgresTestDataSources.QueryKind.VACUUM,
                        PostgresTestDataSources.row(
                                "schema_name",
                                "public",
                                "table_name",
                                "orders",
                                "live_tuples",
                                10L,
                                "dead_tuples",
                                1L,
                                "last_vacuum",
                                new Timestamp(now),
                                "last_autovacuum",
                                null,
                                "last_analyze",
                                null,
                                "last_autoanalyze",
                                null))
                .fail(PostgresTestDataSources.QueryKind.SLOTS, "permission denied for view pg_replication_slots");
        PostgresDatabaseData data = new PostgresDatabaseData("primary");
        var context = PostgresTestDataSources.context(dataSource, 15, exposure());

        List<PostgresSectionDto> sections = List.of(
                new PostgresSettingsCollector().collect(context, data),
                new PostgresVitalSignsCollector().collect(context, data),
                new PostgresStatementCollector().collect(context, data),
                new PostgresIndexCollector().collect(context, data),
                new PostgresTableCollector().collect(context, data),
                new PostgresVacuumCollector().collect(context, data),
                new PostgresReplicationCollector().collect(context, data));

        assertThat(sections)
                .extracting(PostgresSectionDto::id)
                .containsExactly("settings", "vital-signs", "statements", "indexes", "tables", "vacuum", "replication");
        assertThat(sections).extracting(PostgresSectionDto::status).containsOnly("AVAILABLE");
        assertThat(sections.get(6).reason()).contains("Replication slots could not be read", "permission denied");
        assertThat(data.settings()).isNotEmpty();
        assertThat(data.vitalSigns().databaseName()).isEqualTo("app");
        assertThat(data.statements()).singleElement().satisfies(statement -> {
            assertThat(statement.query()).isEqualTo("select '******'");
            assertThat(statement.cacheHitRatio()).isEqualTo(0.9);
        });
        assertThat(data.indexes())
                .singleElement()
                .satisfies(index -> assertThat(index.index()).isEqualTo("orders_idx"));
        assertThat(data.tables())
                .singleElement()
                .satisfies(table -> assertThat(table.sequentialScanRatio()).isEqualTo(0.1));
        assertThat(data.vacuum()).singleElement().satisfies(vacuum -> {
            assertThat(vacuum.vacuumThreshold()).isEqualTo(52L);
            assertThat(vacuum.lastVacuum()).isEqualTo(now);
        });
        assertThat(data.replication().replicationSlots()).isNull();
    }

    private static ExposurePolicy exposure() {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return ValueExposure.MASKED;
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
    }
}
