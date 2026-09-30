package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.activity.SimpleDriverDataSource;
import javax.sql.DataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Live MariaDB coverage for the Live Activity durable store, through MariaDB's own driver (which reports the
 * product name {@code "MariaDB"}, not {@code "MySQL"}). MariaDB only accepts {@code OFFSET ... FETCH FIRST} from 10.6,
 * so the store uses {@code LIMIT} for it as for MySQL. Uses the engine's dedicated-mode {@link SimpleDriverDataSource}.
 * Skips without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class MariaDbActivityStoreLiveTests extends AbstractJdbcActivityStoreLiveTests {

    @Container
    static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4");

    @Override
    protected DataSource dataSource() {
        return new SimpleDriverDataSource(
                MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword(), MARIADB.getDriverClassName());
    }

    @Override
    protected String expectedProductName() {
        return "MariaDB";
    }
}
