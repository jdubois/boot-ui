package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class MySqlPerformanceSchemaOffLiveTests {
    @Container
    static MySQLContainer mysql = MySqlLiveFixture.container().withCommand("--performance-schema=OFF");

    @BeforeAll
    static void initialize() throws Exception {
        MySqlLiveFixture.initialize(mysql);
    }

    @Test
    void catalogSettingsAndServerStatusSurviveWithoutFakeEmptyInstrumentation() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
            var report = MySqlLiveFixture.service(pool).read();
            assertThat(report.status()).isEqualTo("PARTIAL");
            var source = report.dataSources().get(0);
            assertThat(source.tables()).hasSize(2);
            assertThat(source.settings()).hasSize(17);
            assertThat(source.vitalSigns()).hasSize(17);
            assertThat(source.sections()).anySatisfy(section -> {
                assertThat(section.id()).isEqualTo("sessions");
                assertThat(section.status()).isEqualTo("SKIPPED");
                assertThat(section.reason()).contains("disabled");
            });
            assertThat(source.sections()).anySatisfy(section -> {
                assertThat(section.id()).isEqualTo("tables");
                assertThat(section.status()).isEqualTo("AVAILABLE");
                assertThat(section.reason()).contains("disabled");
            });
        }
    }
}
