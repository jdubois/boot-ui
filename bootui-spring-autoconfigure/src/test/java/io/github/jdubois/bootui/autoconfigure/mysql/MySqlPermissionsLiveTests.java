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
class MySqlPermissionsLiveTests {
    @Container
    static MySQLContainer mysql = MySqlLiveFixture.container();

    @BeforeAll
    static void initialize() throws Exception {
        MySqlLiveFixture.initialize(mysql);
    }

    @Test
    void restrictedAccountKeepsCatalogAndUsesBoundedShowAfterARealPermissionFailure() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "metadata_reader")) {
            MySqlLiveFixture.ObservedDataSource observed = new MySqlLiveFixture.ObservedDataSource(pool);
            // MySQL 8.4 permits global_status to this account. Deliberately add a forbidden
            // source in this test hook to provoke a real server permission error on the SELECT
            // path, then prove that the fixed-name SHOW fallback uses the network guard.
            observed.rewrite = query -> query.contains("FROM performance_schema.global_status")
                    ? query.replace(
                            "FROM performance_schema.global_status",
                            "FROM performance_schema.global_status JOIN performance_schema.setup_actors ON 1=1")
                    : query;
            java.util.concurrent.atomic.AtomicBoolean showGuardChecked =
                    new java.util.concurrent.atomic.AtomicBoolean();
            observed.beforeShowStatus = connection -> {
                assertThat(connection.getNetworkTimeout()).isBetween(1, 7000);
                showGuardChecked.set(true);
            };
            var report = MySqlLiveFixture.service(observed).read();
            assertThat(report.status()).isEqualTo("PARTIAL");
            var source = report.dataSources().get(0);
            assertThat(showGuardChecked).isTrue();
            assertThat(source.vitalSigns()).hasSize(17);
            assertThat(source.capabilities()).anySatisfy(capability -> {
                assertThat(capability.id()).isEqualTo("show-global-status");
                assertThat(capability.readability()).isEqualTo("READABLE");
            });
            assertThat(source.tables()).extracting("tableName").contains("sample_orders", "sample_audit");
            assertThat(source.settings()).extracting("name").contains("max_connections", "performance_schema");
            assertThat(source.capabilities()).anySatisfy(capability -> {
                assertThat(capability.source()).isEqualTo("performance_schema.threads");
                assertThat(capability.readability()).isEqualTo("DENIED");
            });
            assertThat(source.sections()).anySatisfy(section -> {
                assertThat(section.id()).isEqualTo("replication");
                assertThat(section.status()).isEqualTo("FAILED");
            });
        }
    }

    @Test
    void tableSpecificThreadsSelectSeesAnotherUserWithoutProcess() throws Exception {
        try (Connection application = java.sql.DriverManager.getConnection(
                        mysql.getJdbcUrl(), "application", MySqlLiveFixture.PASSWORD);
                HikariDataSource reader = MySqlLiveFixture.pool(mysql, "reader")) {
            String applicationId = MySqlLiveFixture.scalar(application, "SELECT CONNECTION_ID()");
            var source = MySqlLiveFixture.service(reader).read().dataSources().get(0);
            assertThat(source.sessions()).anySatisfy(session -> {
                assertThat(session.connectionId()).isEqualTo(applicationId);
                assertThat(session.user()).isEqualTo("application");
            });
            assertThat(source.sections()).anySatisfy(section -> {
                assertThat(section.id()).isEqualTo("sessions");
                assertThat(section.status()).isEqualTo("AVAILABLE");
                assertThat(section.reason()).contains("innodb_trx");
            });
        }
    }

    @Test
    void activeRoleGrantsAreObservedThroughReadsNotShowGrantsParsing() throws Exception {
        try (Connection admin = MySqlLiveFixture.admin(mysql);
                Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE 'bootui_threads_role'");
            sql.execute("GRANT SELECT ON performance_schema.threads TO 'bootui_threads_role'");
            sql.execute("GRANT 'bootui_threads_role' TO 'metadata_reader'@'%'");
            sql.execute("SET DEFAULT ROLE 'bootui_threads_role' TO 'metadata_reader'@'%'");
        }
        try (HikariDataSource reader = MySqlLiveFixture.pool(mysql, "metadata_reader")) {
            var source = MySqlLiveFixture.service(reader).read().dataSources().get(0);
            assertThat(source.capabilities()).anySatisfy(capability -> {
                assertThat(capability.source()).isEqualTo("performance_schema.threads");
                assertThat(capability.readability()).isEqualTo("READABLE");
            });
        } finally {
            try (Connection admin = MySqlLiveFixture.admin(mysql);
                    Statement sql = admin.createStatement()) {
                sql.execute("SET DEFAULT ROLE NONE TO 'metadata_reader'@'%'");
                sql.execute("DROP ROLE 'bootui_threads_role'");
            }
        }
    }
}
