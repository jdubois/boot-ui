package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class MySqlDigestOverflowLiveTests {
    @Container
    static MySQLContainer mysql =
            MySqlLiveFixture.container().withCommand("--performance-schema=ON", "--performance-schema-digests-size=10");

    @BeforeAll
    static void initialize() throws Exception {
        MySqlLiveFixture.initialize(mysql);
    }

    @Test
    void actualServerDigestOverflowIsNotAFalseSchemaCounterOrBootuiRowCap() throws Exception {
        try (Connection admin = MySqlLiveFixture.admin(mysql);
                Statement workload = admin.createStatement();
                HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
            for (int index = 0; index < 30; index++) {
                workload.executeQuery("SELECT 1 AS synthetic_digest_" + index).close();
            }
            assertThat(Long.parseLong(MySqlLiveFixture.scalar(
                            admin,
                            "SELECT COUNT_STAR FROM performance_schema.events_statements_summary_by_digest"
                                    + " WHERE SCHEMA_NAME IS NULL AND DIGEST IS NULL")))
                    .isPositive();
            var report = MySqlLiveFixture.service(pool).read();
            assertThat(report.truncated()).isFalse();
            assertThat(report.dataSources().get(0).sections()).anySatisfy(section -> {
                assertThat(section.id()).isEqualTo("statements");
                assertThat(section.reason()).contains("server-wide null-digest overflow");
            });
        }
    }
}
