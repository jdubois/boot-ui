package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.activity.SimpleDriverDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * Live Oracle coverage for the Live Activity durable store: Oracle has no {@code BIGINT} type (ORA-00902), so the
 * store creates its 64-bit columns as {@code NUMBER(19)} there. Uses Oracle Database Free through the test-scope-only
 * {@code ojdbc11} driver and the engine's dedicated-mode {@link SimpleDriverDataSource}. Skips without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class OracleActivityStoreLiveTests extends AbstractJdbcActivityStoreLiveTests {

    @Container
    // The default 60 s wait is too tight on shared CI runners: Oracle Free can report ready just past it.
    static final OracleContainer ORACLE =
            new OracleContainer("gvenzl/oracle-free:slim-faststart").withStartupTimeout(Duration.ofMinutes(3));

    @Override
    protected DataSource dataSource() {
        return new SimpleDriverDataSource(
                ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword(), ORACLE.getDriverClassName());
    }

    @Override
    protected String expectedProductName() {
        return "Oracle";
    }
}
