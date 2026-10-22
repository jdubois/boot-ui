package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Live PostgreSQL coverage for DB-POOL-001 and DB-POOL-002: the server's {@code pg_settings} (read in the scan's
 * bounded read, with the unit it reports) meet a pool supplied through the evidence SPI. The idle limit is set on the
 * role, which proves the value is the one the scan connection's own session sees. Skips cleanly without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresPoolServerRulesLiveTests {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static DataSource dataSource;

    @BeforeAll
    static void shortIdleLimit() throws SQLException {
        DriverManagerDataSource source =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        dataSource = source;
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            // Applies to sessions opened afterwards, which is every connection the scan borrows.
            statement.execute("alter role \"" + postgres.getUsername() + "\" set idle_session_timeout = '1min'");
            // Role settings reach the scan connection's session; DB-SERVER-001 reads this one, 002 keeps its default 0.
            statement.execute(
                    "alter role \"" + postgres.getUsername() + "\" set idle_in_transaction_session_timeout = '30s'");
        }
    }

    @Test
    void aLongPoolLifetimeAgainstAShortIdleSessionTimeoutFiresDbPool001() {
        PoolServerLiveSupport.assertIdleLimitFires(dataSource, postgres.getPassword(), "idle_session_timeout");
    }

    @Test
    void aShorterLifetimeOrAKeepaliveCoversIt() {
        PoolServerLiveSupport.assertCoveredPoolStaysQuiet(dataSource, postgres.getPassword());
    }

    @Test
    void aPoolLargerThanMaxConnectionsFiresDbPool002() {
        PoolServerLiveSupport.assertOversizedPoolExceedsTheServer(dataSource, postgres.getPassword());
    }

    @Test
    void theServerRulesJudgeTheLiveServerAndTheExtensionReadSeesACreatedExtension() throws SQLException {
        DatabaseAdvisorReport report = PoolServerLiveSupport.scan(
                dataSource, PoolServerLiveSupport.hikari(10, 10, 0, 45_000L, 0), postgres.getPassword());

        // The role sets a 30 s idle-in-transaction timeout; everything else is a stock PostgreSQL default.
        assertThat(status(report, "DB-SERVER-001")).isEqualTo("PASS");
        assertThat(status(report, "DB-SERVER-002")).isEqualTo("VIOLATION");
        assertThat(status(report, "DB-SERVER-003")).isEqualTo("PASS");
        assertThat(status(report, "DB-SERVER-005")).isEqualTo("VIOLATION");
        assertThat(status(report, "DB-SERVER-006")).isEqualTo("VIOLATION");
        assertThat(status(report, "DB-SERVER-007")).isEqualTo("SKIPPED");
        assertThat(PoolServerLiveSupport.rule(report, "DB-SERVER-004"))
                .hasValueSatisfying(
                        result -> assertThat(result.sampleViolations().get(0))
                                .contains("is not in shared_preload_libraries and is not created in this database"));
        assertThat(report.diagnostics())
                .noneMatch(diagnostic -> diagnostic.message().contains("could not be read"));

        // The library is still not preloaded, but the extension row is now read from pg_extension.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create extension if not exists pg_stat_statements");
        }
        DatabaseAdvisorReport created = PoolServerLiveSupport.scan(
                dataSource, PoolServerLiveSupport.hikari(10, 10, 0, 45_000L, 0), postgres.getPassword());
        assertThat(PoolServerLiveSupport.rule(created, "DB-SERVER-004"))
                .hasValueSatisfying(
                        result -> assertThat(result.sampleViolations().get(0))
                                .contains("is not in shared_preload_libraries")
                                .doesNotContain("is not created"));
    }

    private static String status(DatabaseAdvisorReport report, String id) {
        return PoolServerLiveSupport.rule(report, id).orElseThrow().status();
    }
}
