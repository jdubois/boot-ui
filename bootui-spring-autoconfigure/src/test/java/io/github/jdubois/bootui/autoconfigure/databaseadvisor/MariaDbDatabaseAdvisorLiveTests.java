package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
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
        }
    }

    private static DatabaseAdvisorReport scan() {
        return DatabaseAdvisorScanner.using(
                        () -> List.of(new NamedDataSource("primary", dataSource)),
                        () -> EntityDiscovery.empty(null),
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

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("DB-SCHEMA-005");
            assertThat(diagnostic.level()).isEqualTo("WARNING");
            assertThat(diagnostic.message()).contains("index semantics are unknown");
        });
        assertThat(report.rulesErrored()).isZero();
        assertThat(report.dataSources()).singleElement().satisfies(status -> {
            assertThat(status.status()).isEqualTo("AVAILABLE");
            assertThat(status.dialect()).isEqualTo("MariaDB");
        });
        // Every MySQL-family rule ran: none of them is reported as skipped for want of a datasource.
        assertThat(report.diagnostics())
                .noneMatch(diagnostic -> diagnostic.source().startsWith("DB-MYSQL"));
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
}
