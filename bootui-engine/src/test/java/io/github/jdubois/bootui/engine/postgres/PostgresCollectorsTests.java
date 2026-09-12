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
    void statementCollectorPicksTimingColumnsFromTheExtensionNotTheServer() {
        assertThat(PostgresStatementCollector.sql("pg_stat_statements", false))
                .contains("s.total_time as total_time", "s.mean_time as mean_time")
                .doesNotContain("total_exec_time");
        assertThat(PostgresStatementCollector.sql("pg_stat_statements", true))
                .contains("s.total_exec_time as total_time", "s.mean_exec_time as mean_time");
    }

    @Test
    void statementCollectorUsesTheCatalogResolvedViewAndRefusesAnUnexpectedName() {
        assertThat(PostgresStatementCollector.sql("monitoring.pg_stat_statements", true))
                .contains("from monitoring.pg_stat_statements s");
        assertThat(PostgresStatementCollector.sql("\"My Schema\".pg_stat_statements", true))
                .contains("from \"My Schema\".pg_stat_statements s");
        assertThat(PostgresStatementCollector.sql("evil; drop table orders --", true))
                .contains("from pg_stat_statements s")
                .doesNotContain("drop table");
        assertThat(PostgresStatementCollector.sql(null, true)).contains("from pg_stat_statements s");
    }

    @Test
    void replicationCollectorPicksTheCheckpointViewThatExists() {
        assertThat(PostgresReplicationCollector.checkpointSql(false))
                .contains("pg_stat_bgwriter", "checkpoints_req")
                .doesNotContain("pg_stat_checkpointer");
        assertThat(PostgresReplicationCollector.checkpointSql(true)).contains("pg_stat_checkpointer", "num_requested");
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
                        50,
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
                .rows(PostgresTestDataSources.QueryKind.EXTENSION, PostgresTestDataSources.row("relation", null));
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

    @Test
    void aFailedQueryDoesNotPoisonTheSectionsReadAfterIt() throws SQLException {
        // PostgreSQL aborts the enclosing transaction on any statement error; without a per-query
        // savepoint every later collector would report "current transaction is aborted" instead of
        // its own content.
        var dataSource = PostgresTestDataSources.postgres()
                .fail(PostgresTestDataSources.QueryKind.INDEXES, "permission denied for view pg_stat_user_indexes");
        var connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        var context = PostgresTestDataSources.context(connection, 15, exposure());
        PostgresDatabaseData data = new PostgresDatabaseData("primary");

        PostgresSectionDto indexes = new PostgresIndexCollector().collect(context, data);
        PostgresSectionDto tables = new PostgresTableCollector().collect(context, data);

        assertThat(indexes.status()).isEqualTo("FAILED");
        assertThat(tables.status()).isEqualTo("AVAILABLE");
        assertThat(tables.reason()).isNull();
    }

    @Test
    void vitalSignsReportTheSessionBreakdownAsUnknownWhenBackendsAreHidden() throws SQLException {
        var dataSource = PostgresTestDataSources.postgres()
                .rows(
                        PostgresTestDataSources.QueryKind.VITALS,
                        PostgresTestDataSources.row(
                                "database_name",
                                "app",
                                "database_size",
                                1024L,
                                "xact_commit",
                                100L,
                                "xact_rollback",
                                0L,
                                "blks_read",
                                1L,
                                "blks_hit",
                                99L,
                                "backends",
                                40,
                                "deadlocks",
                                0L,
                                "temp_files",
                                0L,
                                "temp_bytes",
                                0L,
                                "xid_age",
                                1L,
                                "freeze_max_age",
                                200000000L,
                                "max_connections",
                                100))
                .rows(
                        PostgresTestDataSources.QueryKind.ACTIVITY,
                        PostgresTestDataSources.row(
                                "active_sessions",
                                1,
                                "idle_in_transaction",
                                0,
                                "blocked_sessions",
                                0,
                                "longest_transaction_seconds",
                                null));
        PostgresDatabaseData data = new PostgresDatabaseData("primary");
        // A role without pg_read_all_stats does not see a nulled pg_stat_activity: PostgreSQL removes the
        // rows of backends it does not own, so the survivors read as a calm, fully active server. Only the
        // privilege probe can tell the difference.
        data.markStatisticsRestricted(true);
        var context = PostgresTestDataSources.context(dataSource, 15, exposure());

        PostgresSectionDto section = new PostgresVitalSignsCollector().collect(context, data);

        assertThat(section.reason()).contains("pg_stat_activity hides the state");
        assertThat(data.vitalSigns().activeSessions()).isNull();
        assertThat(data.vitalSigns().idleInTransactionSessions()).isNull();
        assertThat(data.vitalSigns().blockedSessions()).isNull();
        assertThat(data.vitalSigns().longestTransactionSeconds()).isNull();
        // The connection total survives: it is summed from pg_stat_database, which every role reads in full.
        assertThat(data.vitalSigns().connections()).isEqualTo(40);
    }

    @Test
    void replicaClientAddressFollowsTheExposurePolicy() throws SQLException {
        assertThat(replicaAddress(ValueExposure.MASKED)).isEqualTo("10.0.0.7/32");
        assertThat(replicaAddress(ValueExposure.METADATA_ONLY)).isEqualTo("******");
    }

    @Test
    void settingValuesAreWithheldUnderMetadataOnlyButStillDriveTheRules() throws SQLException {
        var dataSource = PostgresTestDataSources.postgres();
        PostgresDatabaseData data = new PostgresDatabaseData("primary");

        new PostgresSettingsCollector()
                .collect(PostgresTestDataSources.context(dataSource, 15, exposure(ValueExposure.METADATA_ONLY)), data);

        // METADATA_ONLY means no value reaches the browser, MCP or CLI, even for an allow-listed
        // operational setting; the raw value is still kept internally so "due?" stays computed against the
        // server's real configuration rather than assumed defaults.
        assertThat(data.settings())
                .isNotEmpty()
                .allSatisfy(setting -> assertThat(setting.value()).isEqualTo("******"));
        assertThat(data.settingValues()).containsKey("autovacuum_vacuum_threshold");
        assertThat(data.setting("autovacuum_vacuum_threshold")).isNotEqualTo("******");
    }

    private static String replicaAddress(ValueExposure valueExposure) throws SQLException {
        var dataSource = PostgresTestDataSources.postgres()
                .rows(
                        PostgresTestDataSources.QueryKind.REPLICAS,
                        PostgresTestDataSources.row(
                                "application_name",
                                "replica",
                                "client_addr",
                                "10.0.0.7/32",
                                "state",
                                "streaming",
                                "sync_state",
                                "async",
                                "sent_lag",
                                0L,
                                "flush_lag",
                                0L,
                                "replay_lag",
                                0L));
        PostgresDatabaseData data = new PostgresDatabaseData("primary");
        new PostgresReplicationCollector()
                .collect(PostgresTestDataSources.context(dataSource, 15, exposure(valueExposure)), data);
        return data.replication().replicas().get(0).clientAddress();
    }

    private static ExposurePolicy exposure(ValueExposure valueExposure) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return valueExposure;
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        };
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

    @Test
    void liveSessionAgesAreMeasuredFromTheStatementClockRatherThanTheTransactionClock() {
        // Every collector shares one read-only transaction, so now() is frozen at the instant that
        // transaction opened. Verified against PostgreSQL 18.6: three seconds into a read, now() reports an
        // age of -3.00 s for a session whose statement started after the read began — and BootUI's own
        // backend is always in that category, so its row would always show a negative age.
        assertThat(PostgresSessionCollector.SQL).contains("clock_timestamp()").doesNotContain("now()");
        assertThat(PostgresVitalSignsCollector.ACTIVITY_SQL)
                .contains("clock_timestamp()")
                .doesNotContain("now()");
    }

    @Test
    void theSessionListIsScopedToTheDatabaseThePanelReports() {
        // pg_stat_activity spans the whole cluster. Verified against PostgreSQL 18.6: without this filter a
        // backend connected to a different database on the same server appears in the panel, statement text
        // included, while every other section reports only current_database().
        assertThat(PostgresSessionCollector.SQL).contains("a.datname = current_database()");
        assertThat(PostgresVitalSignsCollector.ACTIVITY_SQL).contains("datname = current_database()");
    }
}
