package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStoreFactory;
import io.github.jdubois.bootui.engine.activity.JdbcActivityStore;
import io.github.jdubois.bootui.engine.activity.SimpleDriverDataSource;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Live MySQL coverage for the Live Activity durable store (issue #1142): MySQL rejects the SQL-standard
 * {@code OFFSET ... FETCH FIRST} row limit, so every read must use {@code LIMIT}. The shared scenarios run over a
 * HikariCP pool, as {@code SHARED} mode would reuse a Spring Boot application's own datasource; the extra scenario
 * below covers {@code DEDICATED} mode with a MySQL JDBC URL. CI requires this suite to run rather than skip.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlActivityStoreLiveTests extends AbstractJdbcActivityStoreLiveTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.6");

    private static HikariDataSource pool;

    @BeforeAll
    static void openPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(2);
        pool = new HikariDataSource(config);
    }

    @AfterAll
    static void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    @Override
    protected DataSource dataSource() {
        return pool;
    }

    @Override
    protected String expectedProductName() {
        return "MySQL";
    }

    @Test
    void dedicatedModeWithAMySqlJdbcUrlPersistsEntriesThatReadBack() {
        String table = newTableName();
        ActivityPersistenceSettings settings = new ActivityPersistenceSettings(
                true,
                ActivityPersistenceSettings.DataSourceMode.DEDICATED,
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword(),
                null,
                table,
                Duration.ofMinutes(10),
                200,
                Duration.ofDays(7),
                "app-dedicated",
                Duration.ofSeconds(1));
        SwitchableActivityStore store = ActivityStoreFactory.create(settings, () -> null);
        try {
            store.appendBatch(List.of(
                    stored("app-dedicated", 1, entry("1", "REQUEST", 100, "OK", "first")),
                    stored("app-dedicated", 2, entry("2", "REQUEST", 200, "OK", "second"))));

            assertThat(store.query(ActivityQuery.firstPage("app-dedicated")).entryDtos())
                    .extracting(ActivityEntryDto::id)
                    .containsExactly("2", "1");
        } finally {
            store.close();
        }

        DataSource dedicated = new SimpleDriverDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), MYSQL.getDriverClassName());
        assertThat(new JdbcActivityStore(dedicated, table)
                        .query(ActivityQuery.firstPage("app-dedicated"))
                        .entryDtos())
                .extracting(ActivityEntryDto::id)
                .containsExactly("2", "1");
    }
}
