package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

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
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Live MariaDB Connector/J coverage for DB-POOL-001 and DB-POOL-002. The driver wraps a statement that has a timeout and
 * a row bound in {@code SET STATEMENT}, so {@code show session variables} must be proven through it: it runs through the
 * fixed-statement read of a real driver, and a non-interactive JDBC session inherits the global {@code wait_timeout}
 * that is lowered here before the scan connects. Skips cleanly without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class MariaDbPoolServerRulesLiveTests {

    @Container
    static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.4");

    private static DataSource dataSource;

    @BeforeAll
    static void shortIdleLimit() throws SQLException {
        // The container's test user cannot change global variables; the root password equals the test one.
        try (Connection root = DriverManager.getConnection(mariadb.getJdbcUrl(), "root", mariadb.getPassword());
                Statement statement = root.createStatement()) {
            statement.execute("set global wait_timeout = 60");
        }
        DriverManagerDataSource source =
                new DriverManagerDataSource(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword());
        source.setDriverClassName("org.mariadb.jdbc.Driver");
        dataSource = source;
    }

    @Test
    void aLongPoolLifetimeAgainstAShortWaitTimeoutFiresDbPool001() {
        PoolServerLiveSupport.assertIdleLimitFires(dataSource, mariadb.getPassword(), "wait_timeout");
    }

    @Test
    void aShorterLifetimeOrAKeepaliveCoversIt() {
        PoolServerLiveSupport.assertCoveredPoolStaysQuiet(dataSource, mariadb.getPassword());
    }

    @Test
    void aPoolLargerThanMaxConnectionsFiresDbPool002() {
        PoolServerLiveSupport.assertOversizedPoolExceedsTheServer(dataSource, mariadb.getPassword());
    }
}
