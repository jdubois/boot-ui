package io.github.jdubois.bootui.engine.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
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
        assertThat(report.findings()).isEmpty();
        assertThat(report.evidence().usable()).isFalse();
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
        assertThat(report.findings()).isEmpty();
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
        assertThat(report.evidence().usable()).isFalse();
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
        assertThat(report.findings()).extracting(finding -> finding.id()).contains("PG-STATEMENTS-001");
        assertThat(report.findings())
                .flatExtracting(finding -> finding.samples())
                .allSatisfy(sample -> assertThat(sample).doesNotContain("secret"));
    }

    @Test
    void statementRulesAreNotEvaluatedWhenTheirSectionIsSkipped() {
        var dataSource = PostgresTestDataSources.postgres()
                .rows(PostgresTestDataSources.QueryKind.EXTENSION, PostgresTestDataSources.row("installed", 0))
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
                        .satisfies(section -> assertThat(section.status()).isEqualTo("SKIPPED")));
        assertThat(report.findings()).noneMatch(finding -> finding.id().startsWith("PG-STATEMENTS-"));
    }

    @Test
    void aRefusedSessionPinIsRolledBackSoTheReadStillCompletes() {
        var dataSource = PostgresTestDataSources.postgres()
                .failPin("statement_timeout", "permission denied to set parameter for jdbc:******db/app");

        PostgresInsightReport report =
                service(() -> discovery("primary", dataSource)).read();

        assertThat(report.databases()).singleElement().satisfies(database -> {
            assertThat(database.status()).isEqualTo("SCANNED");
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
    void findingsAreSortedByImportanceThenDatasourceThenRuleId() {
        var first = unhealthy(PostgresTestDataSources.postgres());
        var second = unhealthy(PostgresTestDataSources.postgres());

        PostgresInsightReport report = service(() -> new DatabaseAdvisorDataSourceDiscovery(
                        List.of(new NamedDataSource("b", second), new NamedDataSource("a", first)), List.of()))
                .read();

        assertThat(report.findings()).extracting(finding -> finding.severity()).startsWith("CRITICAL", "CRITICAL");
        assertThat(report.findings())
                .extracting(finding -> finding.dataSource())
                .startsWith("a", "b");
        assertThat(report.findings())
                .extracting(finding -> finding.id())
                .containsSubsequence("PG-VITALS-006", "PG-SETTINGS-001", "PG-SETTINGS-002");
        assertThat(report.severityCounts()).isNotEmpty();
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

    private static PostgresTestDataSources.ScriptedDataSource unhealthy(
            PostgresTestDataSources.ScriptedDataSource dataSource) {
        return dataSource
                .rows(PostgresTestDataSources.QueryKind.VITALS, vitals(1000L, 1000L, 1024L))
                .rows(
                        PostgresTestDataSources.QueryKind.SETTINGS,
                        PostgresTestDataSources.row(
                                "name", "autovacuum", "setting", "off", "unit", null, "source", "default"),
                        PostgresTestDataSources.row(
                                "name", "fsync", "setting", "off", "unit", null, "source", "default"),
                        PostgresTestDataSources.row(
                                "name", "full_page_writes", "setting", "off", "unit", null, "source", "default"),
                        PostgresTestDataSources.row(
                                "name", "track_io_timing", "setting", "off", "unit", null, "source", "default"));
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
