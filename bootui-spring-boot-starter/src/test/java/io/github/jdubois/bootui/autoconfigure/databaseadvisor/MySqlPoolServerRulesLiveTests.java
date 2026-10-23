package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Live MySQL Connector/J coverage for DB-POOL-001 and DB-POOL-002: {@code show session variables} runs through the
 * fixed-statement read of a real driver, and a non-interactive JDBC session inherits the global {@code wait_timeout}
 * that is lowered here before the scan connects. Skips cleanly without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlPoolServerRulesLiveTests {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4.6");

    private static DataSource dataSource;

    @BeforeAll
    static void shortIdleLimit() throws SQLException {
        // The container's test user cannot change global variables; the root password equals the test one.
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                Statement statement = root.createStatement()) {
            statement.execute("set global wait_timeout = 60");
        }
        DriverManagerDataSource source =
                new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource = source;
    }

    @Test
    void aLongPoolLifetimeAgainstAShortWaitTimeoutFiresDbPool001() {
        PoolServerLiveSupport.assertIdleLimitFires(dataSource, mysql.getPassword(), "wait_timeout");
    }

    @Test
    void aShorterLifetimeOrAKeepaliveCoversIt() {
        PoolServerLiveSupport.assertCoveredPoolStaysQuiet(dataSource, mysql.getPassword());
    }

    @Test
    void aPoolLargerThanMaxConnectionsFiresDbPool002() {
        PoolServerLiveSupport.assertOversizedPoolExceedsTheServer(dataSource, mysql.getPassword());
    }

    @Test
    void performanceSchemaIsReadFromTheLiveServerAndPassesWhenOn() {
        DatabaseAdvisorReport report = PoolServerLiveSupport.scan(
                dataSource, PoolServerLiveSupport.hikari(10, 10, 0, 45_000L, 0), mysql.getPassword());

        assertThat(PoolServerLiveSupport.outcome(report, "DB-SERVER-007")).isEqualTo("PASS");
    }
}
