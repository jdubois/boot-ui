package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateAttributeModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateEntityModel;
import io.github.jdubois.bootui.spi.NamedDataSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Live MariaDB coverage for the Database Advisor.
 *
 * <p>MariaDB is the reason this test exists rather than a MySQL one: it reports
 * {@code getDatabaseProductName() == "MariaDB"} but shares MySQL's {@code information_schema} shape with one
 * critical difference — index visibility is {@code IGNORED}, not MySQL 8.0's {@code IS_VISIBLE}, and there is
 * no {@code EXPRESSION} column at all. Selecting a column that does not exist fails the entire statement, so
 * "the catalog SQL matches this server" is exactly the kind of claim that must be proven against a real
 * server. Until this change MariaDB was not detected as a MySQL-family dialect at all and none of the
 * MySQL rules ran against it.</p>
 *
 * <p>Reuses the Testcontainers setup already used for PostgreSQL and skips cleanly without Docker.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class MariaDbDatabaseAdvisorLiveTests {

    @Container
    static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.4");

    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() throws SQLException {
        DriverManagerDataSource source =
                new DriverManagerDataSource(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword());
        source.setDriverClassName("org.mariadb.jdbc.Driver");
        dataSource = source;
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            // A non-transactional storage engine and a legacy three-byte utf8 default.
            statement.execute("create table legacy_sessions (id int primary key, payload varchar(255)) "
                    + "engine = MyISAM default charset = utf8mb3");
            // An AUTO_INCREMENT column already deep into its signed int range.
            statement.execute("create table tickets (id int not null auto_increment primary key, "
                    + "label varchar(64) not null) engine = InnoDB default charset = utf8mb4 "
                    + "auto_increment = 2100000000");
            // A foreign key with no supporting index would be rejected by InnoDB, so the FK-index rule is
            // covered by the engine tests; here the index metadata itself is what matters.
            statement.execute("create table orders (id bigint not null primary key, "
                    + "customer_ref varchar(64) not null, index ix_customer_ref (customer_ref(10))) "
                    + "engine = InnoDB default charset = utf8mb4");
            // MariaDB 10.6+ ignored index (DB-SCHEMA-010) and Hibernate's MySQL-family sequence-emulation table,
            // a framework-generated one-row table with no primary key that DB-SCHEMA-001 must not report.
            statement.execute("create index ix_tickets_label on tickets (label)");
            statement.execute("alter table tickets alter index ix_tickets_label ignored");
            statement.execute("create table customer_seq (next_val bigint) engine = InnoDB");
            statement.execute("create table index_comparison (id bigint not null primary key, "
                    + "a varchar(64) not null, b varchar(64) not null, "
                    + "generated_a varchar(64) as (upper(a)) stored, unique index uq_id (id), "
                    + "index ix_ab (a, b), index ix_same (a, b), index ix_reordered (b, a), "
                    + "index ix_desc (a, b desc), index ix_prefix (a(10), b), "
                    + "index ix_generated (generated_a, b), index ix_ignored (a, b)) engine = InnoDB");
            statement.execute("alter table index_comparison alter index ix_ignored ignored");
        }
    }

    private static DatabaseAdvisorReport scan() {
        return DatabaseAdvisorScanner.using(
                        () -> List.of(new NamedDataSource("primary", dataSource)),
                        () -> EntityDiscovery.absent(null),
                        Clock.systemUTC())
                .scan();
    }

    private static Optional<DatabaseAdvisorRuleResultDto> finding(DatabaseAdvisorReport report, String ruleId) {
        return report.results().stream()
                .filter(result -> result.id().equals(ruleId))
                .findFirst();
    }

    @Test
    void detectsMariaDbAsItsOwnDialectAndReadsItsCatalogWithoutErrors() {
        DatabaseAdvisorReport report = scan();

        assertThat(report.scan().status()).as("%s", report.diagnostics()).isEqualTo("SCANNED");
        assertThat(report.diagnostics())
                .noneMatch(diagnostic ->
                        "DB-SCHEMA-005".equals(diagnostic.source()) && "WARNING".equals(diagnostic.level()));
        assertThat(report.rulesErrored()).isZero();
        assertThat(report.dataSources()).singleElement().satisfies(status -> {
            assertThat(status.status()).isEqualTo("AVAILABLE");
            assertThat(status.dialect()).isEqualTo("MariaDB");
        });
        // Every MySQL-family schema rule ran: only DB-MYSQL-004 is skipped as not applicable on MariaDB.
        assertThat(report.diagnostics())
                .filteredOn(diagnostic -> diagnostic.source().startsWith("DB-MYSQL"))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.source()).isEqualTo("DB-MYSQL-004");
                    assertThat(diagnostic.level()).isEqualTo("INFO");
                    assertThat(diagnostic.message()).startsWith("Not applicable:");
                });
    }

    @Entity
    @Table(name = "tickets")
    static class Ticket {
        @Id
        @Column(name = "id", unique = true)
        Integer id;

        @Column(name = "label", unique = true, length = 64)
        String label;
    }

    @Test
    void declaredUniquenessConcludesAgainstTheLiveMariaDbCatalog() {
        List<HibernateAttributeModel> attributes = new java.util.ArrayList<>();
        for (Field field : Ticket.class.getDeclaredFields()) {
            attributes.add(new HibernateAttributeModel(
                    "Ticket",
                    field.getName(),
                    field.getType(),
                    field.getGenericType(),
                    "BASIC",
                    false,
                    true,
                    Arrays.asList(field.getAnnotations())));
        }
        EntityDiscovery entities = new EntityDiscovery(
                List.of(new HibernateEntityModel("Ticket", Ticket.class, attributes)), List.of(), List.of());
        DatabaseAdvisorReport report = DatabaseAdvisorScanner.using(
                        () -> List.of(new NamedDataSource("primary", dataSource)), () -> entities, Clock.systemUTC())
                .scan();

        assertThat(finding(report, "DB-HIB-005")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations())
                    .singleElement()
                    .asString()
                    .contains("Ticket#label", "no enforcing key");
        });
        assertThat(report.diagnostics()).noneMatch(diagnostic -> "DB-HIB-005".equals(diagnostic.source()));
    }

    @Test
    void reportsANonTransactionalStorageEngineOnMariaDb() {
        assertThat(finding(scan(), "DB-MYSQL-001"))
                .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                        .anyMatch(detail -> detail.contains("legacy_sessions") && detail.contains("MyISAM")));
    }

    @Test
    void reportsTheLegacyUtf8mb3TableDefaultButNotUtf8mb4Tables() {
        assertThat(finding(scan(), "DB-MYSQL-002")).hasValueSatisfying(result -> {
            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("legacy_sessions"));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("tickets"));
        });
    }

    @Test
    void reportsAnAutoIncrementColumnNearingItsSignedIntCapacity() {
        assertThat(finding(scan(), "DB-MYSQL-003"))
                .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                        .anyMatch(detail -> detail.contains("tickets.id") && detail.contains("AUTO_INCREMENT")));
    }

    @Test
    void skipsThePostgresRulesWithAnExplicitReasonInsteadOfReportingThemClean() {
        DatabaseAdvisorReport report = scan();

        assertThat(report.results()).noneMatch(result -> result.id().startsWith("DB-PG"));
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.source()).isEqualTo("DB-PG-001"));
    }

    @Test
    void reportsIgnoredIndexesFromTheLiveCatalog() {
        assertThat(finding(scan(), "DB-SCHEMA-010")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations())
                    .hasSize(2)
                    .anySatisfy(detail -> assertThat(detail).contains("ix_tickets_label", "is IGNORED"));
        });
    }

    @Test
    void comparesOnlyExactOrdinaryDefinitionsAndTheProvenPrimaryKeyBackingIndex() {
        DatabaseAdvisorReport report = scan();
        assertThat(finding(report, "DB-SCHEMA-003")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations())
                    .singleElement()
                    .asString()
                    .contains("index_comparison", "ix_ab and ix_same")
                    .doesNotContain("ix_reordered", "ix_desc", "ix_prefix", "ix_generated", "ix_ignored");
        });
        assertThat(finding(report, "DB-SCHEMA-005")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations())
                    .singleElement()
                    .asString()
                    .contains("index_comparison", "uq_id", "PRIMARY");
        });
        assertThat(report.diagnostics())
                .noneMatch(
                        diagnostic -> List.of("DB-SCHEMA-003", "DB-SCHEMA-005").contains(diagnostic.source()));
    }

    @Test
    void temporalUniqueAndUnsupportedStorageDefinitionsRemainUnknownOnTheRealCatalog() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            try {
                statement.execute(
                        "create table temporal_comparison (id bigint not null primary key, room int not null, "
                                + "checkin date not null, checkout date not null, period for p (checkin, checkout), "
                                + "unique index uq_no_overlap (room, p without overlaps), "
                                + "unique index uq_plain (room, checkout, checkin)) engine = InnoDB");
                statement.execute(
                        "create table unsupported_comparison (id bigint not null primary key, a int not null, "
                                + "index ix_first (a), index ix_same (a)) engine = MyISAM");
                java.util.Map<String, List<String>> columns = new java.util.LinkedHashMap<>();
                try (var rows = connection
                        .getMetaData()
                        .getIndexInfo(connection.getCatalog(), null, "temporal_comparison", false, true)) {
                    while (rows.next()) {
                        String name = rows.getString("INDEX_NAME");
                        if ("uq_no_overlap".equals(name) || "uq_plain".equals(name)) {
                            columns.computeIfAbsent(name, ignored -> new java.util.ArrayList<>())
                                    .add(rows.getString("COLUMN_NAME"));
                        }
                    }
                }
                assertThat(columns).hasSize(2);
                assertThat(columns.get("uq_no_overlap")).containsExactly("room", "checkout", "checkin");
                assertThat(columns.get("uq_plain")).isEqualTo(columns.get("uq_no_overlap"));

                DatabaseAdvisorReport report = scan();
                assertThat(finding(report, "DB-SCHEMA-003"))
                        .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                                .noneMatch(detail -> detail.contains("temporal_comparison")
                                        || detail.contains("unsupported_comparison")));
                assertThat(report.diagnostics())
                        .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                                .contains("temporal_comparison", "uq_no_overlap", "incomplete or unsupported"))
                        .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                                .contains("temporal_comparison", "uq_plain", "incomplete or unsupported"))
                        .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                                .contains("unsupported_comparison", "ix_first", "incomplete or unsupported"));
                assertThat(report.diagnostics())
                        .noneMatch(diagnostic -> diagnostic.source().equals("DB-SCHEMA-005")
                                && diagnostic.message().contains("temporal_comparison"));
            } finally {
                statement.execute("drop table if exists temporal_comparison");
                statement.execute("drop table if exists unsupported_comparison");
            }
        }
    }

    @Test
    void shortTemporalUniqueKeysDivergingFromCompositePrimaryKeyDoNotWarnAboutPrimaryKeyOverlap() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            try {
                statement.execute("create table composite_temporal_comparison (tenant int not null, room int not null, "
                        + "id bigint not null, checkin date not null, checkout date not null, "
                        + "primary key (tenant, room, id), unique index uq_dates (checkin, checkout), "
                        + "unique index uq_same_dates (checkin, checkout)) engine = InnoDB");
                java.util.Map<String, List<String>> columns = new java.util.LinkedHashMap<>();
                try (var rows = connection
                        .getMetaData()
                        .getIndexInfo(connection.getCatalog(), null, "composite_temporal_comparison", false, true)) {
                    while (rows.next()) {
                        columns.computeIfAbsent(rows.getString("INDEX_NAME"), ignored -> new java.util.ArrayList<>())
                                .add(rows.getString("COLUMN_NAME"));
                    }
                }
                assertThat(columns.get("PRIMARY")).containsExactly("tenant", "room", "id");
                assertThat(columns.get("uq_dates")).containsExactly("checkin", "checkout");
                assertThat(columns.get("uq_same_dates")).isEqualTo(columns.get("uq_dates"));

                DatabaseAdvisorReport report = scan();
                assertThat(report.dataSources())
                        .singleElement()
                        .satisfies(status -> assertThat(status.status())
                                .as("%s", report.diagnostics())
                                .isEqualTo("AVAILABLE"));
                assertThat(report.diagnostics())
                        .anySatisfy(diagnostic -> {
                            assertThat(diagnostic.source()).isEqualTo("DB-SCHEMA-003");
                            assertThat(diagnostic.message()).contains("composite_temporal_comparison", "uq_dates");
                        })
                        .noneMatch(diagnostic -> diagnostic.source().equals("DB-SCHEMA-005")
                                && diagnostic.message().contains("composite_temporal_comparison"));
                assertThat(finding(report, "DB-SCHEMA-005"))
                        .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                                .noneMatch(detail -> detail.contains("composite_temporal_comparison")));
            } finally {
                statement.execute("drop table if exists composite_temporal_comparison");
            }
        }
    }

    @Test
    void hibernateSequenceEmulationTablesAreNotPrimaryKeyFindings() {
        assertThat(finding(scan(), "DB-SCHEMA-001")
                        .map(DatabaseAdvisorRuleResultDto::sampleViolations)
                        .orElse(List.of()))
                .noneMatch(detail -> detail.contains("customer_seq"));
    }
}
