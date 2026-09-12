package io.github.jdubois.bootui.engine.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.PostgresChangeDto;
import io.github.jdubois.bootui.core.dto.PostgresInsightReport;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgresInsightServiceTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void initialReportTouchesNeitherDiscoveryNorDatabase() {
        AtomicInteger discoveries = new AtomicInteger();
        var dataSource = PostgresTestDataSources.postgres();
        PostgresInsightService service = service(() -> {
            discoveries.incrementAndGet();
            return discovery("primary", dataSource);
        });

        PostgresInsightReport report = service.initialReport();

        assertThat(report.status()).isEqualTo("NOT_READ");
        assertThat(report.readAt()).isNull();
        assertThat(report.databases()).isEmpty();
        assertThat(report.limitations()).isEmpty();
        assertThat(discoveries).hasValue(0);
        assertThat(dataSource.connections()).isZero();
    }

    @Test
    void nonPostgresDatasourceIsReportedHonestlyWithoutADatabaseReport() {
        PostgresInsightReport report = service(() -> discovery("h2", PostgresTestDataSources.nonPostgres()))
                .read();

        assertThat(report.status()).isEqualTo("DISABLED");
        assertThat(report.message()).contains("No PostgreSQL datasource was found");
        assertThat(report.databases()).isEmpty();
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("h2");
            assertThat(diagnostic.level()).isEqualTo("INFO");
            assertThat(diagnostic.message()).contains("not PostgreSQL");
        });
    }

    @Test
    void datasourceThatRefusesConnectionsProducesErrorAndDiagnostic() {
        PostgresInsightReport report = service(() ->
                        discovery("broken", PostgresTestDataSources.failing("connection failed for jdbc:******db/app")))
                .read();

        assertThat(report.status()).isEqualTo("ERROR");
        assertThat(report.databases()).singleElement().satisfies(database -> {
            assertThat(database.name()).isEqualTo("broken");
            assertThat(database.status()).isEqualTo("ERROR");
            assertThat(database.message()).contains("******db/app").doesNotContain("sup3rs3cret");
        });
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo("ERROR");
            assertThat(diagnostic.message()).contains("******db/app").doesNotContain("sup3rs3cret");
        });
    }

    @Test
    void statementTextGoesThroughExposurePolicy() {
        var dataSource = PostgresTestDataSources.postgres()
                .rows(
                        PostgresTestDataSources.QueryKind.STATEMENTS,
                        PostgresTestDataSources.row(
                                "query_id",
                                "42",
                                "query",
                                "select * from users where password = 'secret'",
                                "calls",
                                20L,
                                "total_time",
                                2000d,
                                "mean_time",
                                100d,
                                "max_time",
                                150d,
                                "rows_returned",
                                1L,
                                "shared_blks_hit",
                                1L,
                                "shared_blks_read",
                                0L));
        PostgresInsightService service = PostgresInsightService.using(
                () -> discovery("primary", dataSource),
                exposure(ValueExposure.MASKED, true),
                FIXED_CLOCK,
                PostgresTestDataSources.limits());

        PostgresInsightReport report = service.read();

        assertThat(report.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.statements())
                        .singleElement()
                        .satisfies(statement -> assertThat(statement.query())
                                .isEqualTo("select * from users where password = '******'")));
    }

    @Test
    void theSessionSnapshotIsReportedAsRowsAndItsStatementTextIsMasked() {
        var dataSource = PostgresTestDataSources.postgres()
                .rows(
                        PostgresTestDataSources.QueryKind.SESSIONS,
                        PostgresTestDataSources.row(
                                "pid",
                                4242,
                                "user_name",
                                "app",
                                "application_name",
                                "sample-app",
                                "client_address",
                                "127.0.0.1",
                                "state",
                                "active",
                                "wait_event_type",
                                "Lock",
                                "wait_event",
                                "transactionid",
                                "blocked_by",
                                "17",
                                "state_seconds",
                                12d,
                                "transaction_seconds",
                                30d,
                                "query_seconds",
                                12d,
                                "query",
                                "update accounts set token = 'sup3rs3cret' where id = 1"));

        PostgresInsightReport report =
                service(() -> discovery("primary", dataSource)).read();

        assertThat(report.databases()).singleElement().satisfies(database -> {
            assertThat(database.sections())
                    .filteredOn(section -> section.id().equals(PostgresSectionIds.SESSIONS))
                    .singleElement()
                    .satisfies(section -> {
                        assertThat(section.status()).isEqualTo("AVAILABLE");
                        assertThat(section.rowCount()).isEqualTo(1);
                    });
            assertThat(database.sessions()).singleElement().satisfies(session -> {
                assertThat(session.pid()).isEqualTo(4242);
                assertThat(session.waitEventType()).isEqualTo("Lock");
                assertThat(session.blockedBy()).isEqualTo("17");
                assertThat(session.query()).doesNotContain("sup3rs3cret").contains("update accounts");
            });
        });
    }

    @Test
    void aRoleThatCannotReadOtherBackendsDegradesTheSessionListInsteadOfLookingQuiet() {
        // Verified against PostgreSQL 18.6: a role without pg_read_all_stats does not receive a
        // pg_stat_activity with nulled columns, it receives a shorter result set — three client backends for
        // a superuser, one for an unprivileged role. Nothing in the rows themselves reveals the omission, so
        // a section that trusted the result set would report a calm, single-session server as fully read.
        DataSource dataSource = PostgresTestDataSources.postgres()
                .rows(
                        PostgresTestDataSources.QueryKind.ROLE,
                        PostgresTestDataSources.row("role_name", "app", "monitoring", false));

        PostgresInsightReport report =
                service(() -> discovery("primary", dataSource)).read();

        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.limitations())
                .anySatisfy(limitation ->
                        assertThat(limitation).contains("pg_stat_activity hides the backends this role does not own"));
        assertThat(report.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.sections())
                        .filteredOn(section -> "sessions".equals(section.id()))
                        .singleElement()
                        .satisfies(section -> assertThat(section.reason())
                                .contains("pg_stat_activity hides the backends this role does not own")));
    }

    @Test
    void aRestrictedRoleAlsoDegradesTheStatementRankingItCannotFullyIdentify() {
        // pg_stat_statements restricts the opposite way to pg_stat_activity: it keeps every row, with real
        // call counts and timings, and replaces only the text with "<insufficient privilege>". Trusting the
        // row count alone would present that placeholder as the application's top statement.
        DataSource dataSource = PostgresTestDataSources.postgres()
                .rows(
                        PostgresTestDataSources.QueryKind.ROLE,
                        PostgresTestDataSources.row("role_name", "app", "monitoring", false));

        PostgresInsightReport report =
                service(() -> discovery("primary", dataSource)).read();

        assertThat(report.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.sections())
                        .filteredOn(section -> "statements".equals(section.id()))
                        .singleElement()
                        .satisfies(section -> assertThat(section.reason()).contains("insufficient privilege")));
    }

    @Test
    void anAbsentStatementExtensionIsReportedAsASkippedSectionRatherThanAnEmptyOne() {
        var dataSource = PostgresTestDataSources.postgres()
                .rows(PostgresTestDataSources.QueryKind.EXTENSION, PostgresTestDataSources.row("relation", null))
                .rows(
                        PostgresTestDataSources.QueryKind.STATEMENTS,
                        PostgresTestDataSources.row(
                                "query_id",
                                "42",
                                "query",
                                "select pg_sleep(1)",
                                "calls",
                                20L,
                                "total_time",
                                2000d,
                                "mean_time",
                                100d,
                                "max_time",
                                150d,
                                "rows_returned",
                                1L,
                                "shared_blks_hit",
                                1L,
                                "shared_blks_read",
                                0L));

        PostgresInsightReport report =
                service(() -> discovery("primary", dataSource)).read();

        assertThat(report.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.sections())
                        .filteredOn(section -> section.id().equals(PostgresSectionIds.STATEMENTS))
                        .singleElement()
                        .satisfies(section -> {
                            assertThat(section.status()).isEqualTo("SKIPPED");
                            assertThat(section.hint()).contains("CREATE EXTENSION pg_stat_statements");
                        }));
        assertThat(report.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.statements()).isEmpty());
    }

    @Test
    void aRefusedSessionPinIsRolledBackButTheReadIsNotReportedAsFullyBounded() {
        var dataSource = PostgresTestDataSources.postgres()
                .failPin("statement_timeout", "permission denied to set parameter for jdbc:******db/app");

        PostgresInsightReport report =
                service(() -> discovery("primary", dataSource)).read();

        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("statement_timeout"));
        assertThat(report.databases()).singleElement().satisfies(database -> {
            // The bounds are what make this read safe against a live server, so a read taken without them
            // must not claim to be a clean, fully bounded scan.
            assertThat(database.status()).isEqualTo("PARTIAL");
            assertThat(database.message()).contains("statement_timeout");
            assertThat(database.sections())
                    .filteredOn(section -> section.id().equals(PostgresSectionIds.VITAL_SIGNS))
                    .singleElement()
                    .satisfies(section -> assertThat(section.status()).isEqualTo("AVAILABLE"));
        });
        assertThat(report.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.level()).isEqualTo("WARNING");
            assertThat(diagnostic.message())
                    .contains("statement_timeout")
                    .contains("******db/app")
                    .doesNotContain("sup3rs3cret");
        });
        // The pins that follow the refused one still run, which is only possible because the failed pin was
        // rolled back to its own savepoint: an aborted PostgreSQL transaction rejects every later statement.
        assertThat(dataSource.executedSql()).contains("set local lock_timeout = '2000ms'");
    }

    @Test
    void anExhaustedReadBudgetIsReportedInsteadOfLookingLikeACleanRead() {
        PostgresInsightLimits noBudget = new PostgresInsightLimits(
                50,
                25,
                50,
                25,
                25,
                10,
                40,
                400,
                java.time.Duration.ZERO,
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(2));
        PostgresInsightReport report = PostgresInsightService.using(
                        () -> discovery("primary", PostgresTestDataSources.postgres()),
                        exposure(ValueExposure.MASKED, true),
                        FIXED_CLOCK,
                        noBudget)
                .read();

        assertThat(report.status()).isEqualTo("ERROR");
        assertThat(report.message()).contains("read budget ran out");
        assertThat(report.databases()).isEmpty();
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("primary");
            assertThat(diagnostic.level()).isEqualTo("WARNING");
        });
    }

    @Test
    void inMemoryPreviousReadDeltaAppearsOnlyFromSecondReadAndIsNotPersisted() {
        var dataSource = PostgresTestDataSources.postgres()
                .rows(PostgresTestDataSources.QueryKind.VITALS, vitals(90L, 10L, 1024L));
        PostgresInsightService service = service(() -> discovery("primary", dataSource));

        PostgresInsightReport first = service.read();
        dataSource.rows(PostgresTestDataSources.QueryKind.VITALS, vitals(95L, 5L, 2048L));
        PostgresInsightReport second = service.read();
        PostgresInsightReport newServiceFirstRead =
                service(() -> discovery("primary", dataSource)).read();

        assertThat(first.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.changes()).isEmpty());
        assertThat(second.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.changes())
                        .extracting(change -> change.metric())
                        .contains("Cache hit ratio", "Database size"));
        assertThat(newServiceFirstRead.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.changes()).isEmpty());
    }

    @Test
    void aReadThatProducedNoMetricsDoesNotDiscardTheComparisonBaseline() {
        var healthy = PostgresTestDataSources.postgres()
                .rows(PostgresTestDataSources.QueryKind.VITALS, vitals(90L, 10L, 1024L));
        var moved = PostgresTestDataSources.postgres()
                .rows(PostgresTestDataSources.QueryKind.VITALS, vitals(95L, 5L, 2048L));
        DataSource[] current = {healthy};
        PostgresInsightService service = service(() -> discovery("primary", current[0]));

        service.read();
        // A datasource that could not be reached at all produces no comparable metric. Replacing the
        // baseline with that empty read would silently throw away the comparison the next healthy read owes
        // the user.
        current[0] = PostgresTestDataSources.failing("connection failed for jdbc:******db/app");
        service.read();
        current[0] = moved;
        PostgresInsightReport afterRecovery = service.read();

        assertThat(afterRecovery.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.changes())
                        .extracting(PostgresChangeDto::metric)
                        .contains("Cache hit ratio", "Database size"));
    }

    @Test
    void aPartialReadKeepsTheBaselineOfTheSectionsItCouldNotRead() {
        var healthy = PostgresTestDataSources.postgres()
                .rows(PostgresTestDataSources.QueryKind.VITALS, vitals(90L, 10L, 1024L))
                .rows(PostgresTestDataSources.QueryKind.TABLES, table(1L));
        var moved = PostgresTestDataSources.postgres()
                .rows(PostgresTestDataSources.QueryKind.VITALS, vitals(95L, 5L, 2048L))
                .rows(PostgresTestDataSources.QueryKind.TABLES, table(2L));
        // A read whose vital signs failed still produces the relation metrics, so it is not an empty read.
        // Letting it replace the whole baseline would erase the cache and size values it never read, and the
        // next healthy read would then report "no change" for numbers that had in fact moved.
        var blind = PostgresTestDataSources.postgres()
                .rows(PostgresTestDataSources.QueryKind.TABLES, table(1L))
                .fail(PostgresTestDataSources.QueryKind.VITALS, "permission denied for view pg_stat_database");
        DataSource[] current = {healthy};
        PostgresInsightService service = service(() -> discovery("primary", current[0]));

        service.read();
        current[0] = blind;
        service.read();
        current[0] = moved;
        PostgresInsightReport afterRecovery = service.read();

        assertThat(afterRecovery.databases())
                .singleElement()
                .satisfies(database -> assertThat(database.changes())
                        .extracting(PostgresChangeDto::metric)
                        .contains("Cache hit ratio", "Database size"));
    }

    private static java.util.Map<String, Object> table(long deadTuples) {
        return PostgresTestDataSources.row(
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
                deadTuples,
                "sequential_scans",
                1L,
                "sequential_tuples",
                10L,
                "index_scans",
                9L);
    }

    private static java.util.Map<String, Object> vitals(long blocksHit, long blocksRead, long databaseSize) {
        return PostgresTestDataSources.row(
                "database_name",
                "app",
                "database_size",
                databaseSize,
                "xact_commit",
                100L,
                "xact_rollback",
                0L,
                "blks_read",
                blocksRead,
                "blks_hit",
                blocksHit,
                "deadlocks",
                0L,
                "temp_files",
                0L,
                "temp_bytes",
                0L,
                "xid_age",
                1000L,
                "freeze_max_age",
                2000L,
                "max_connections",
                100);
    }

    private static PostgresInsightService service(Supplier<DatabaseAdvisorDataSourceDiscovery> supplier) {
        return PostgresInsightService.using(
                supplier, exposure(ValueExposure.MASKED, true), FIXED_CLOCK, PostgresTestDataSources.limits());
    }

    @Test
    void aDatasourceThatCouldNotBeDiscoveredKeepsTheReadFromLookingComplete() {
        DatabaseAdvisorDataSourceDiscovery partialDiscovery = new DatabaseAdvisorDataSourceDiscovery(
                List.of(new NamedDataSource("primary", PostgresTestDataSources.postgres())),
                List.of(new DatabaseAdvisorDataSourceDiscovery.Failure("secondary", "bean creation failed")));

        PostgresInsightReport report = service(() -> partialDiscovery).read();

        // The datasource that never became inspectable is part of the application, so a report that reached
        // only the others has not covered it.
        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("secondary"));
    }

    private static DatabaseAdvisorDataSourceDiscovery discovery(String name, DataSource dataSource) {
        return new DatabaseAdvisorDataSourceDiscovery(List.of(new NamedDataSource(name, dataSource)), List.of());
    }

    private static ExposurePolicy exposure(ValueExposure valueExposure, boolean maskSecrets) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return valueExposure;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
    }
}
