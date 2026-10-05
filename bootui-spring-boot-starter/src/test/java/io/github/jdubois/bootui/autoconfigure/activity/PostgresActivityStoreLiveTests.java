package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.activity.SimpleDriverDataSource;
import javax.sql.DataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Live PostgreSQL coverage for the Live Activity durable store, which uses the SQL-standard {@code BIGINT} columns
 * and {@code OFFSET ... FETCH FIRST} row limit there. Uses the engine's dedicated-mode {@link SimpleDriverDataSource}.
 * Skips without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresActivityStoreLiveTests extends AbstractJdbcActivityStoreLiveTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Override
    protected DataSource dataSource() {
        return new SimpleDriverDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), POSTGRES.getDriverClassName());
    }

    @Override
    protected String expectedProductName() {
        return "PostgreSQL";
    }
}
