package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

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
}
