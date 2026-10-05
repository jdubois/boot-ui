package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Actual Connector/J + Hikari transaction, timeout and physical-discard evidence, never a fake driver. */
@Testcontainers(disabledWithoutDocker = true)
class MySqlExecutionLiveTests {
    @Container
    static MySQLContainer mysql = MySqlLiveFixture.container();

    @BeforeAll
    static void initialize() throws Exception {
        MySqlLiveFixture.initialize(mysql);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "readOnlyPropagatesToServer=false&enableQueryTimeouts=false",
                "queryTimeoutKillsConnection=true"
            })
    void serverActuallyRejectsWritesAndNextBorrowerHasOriginalState(String driverOptions) throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "application", driverOptions)) {
            String connectionId;
            try (Connection connection = pool.getConnection();
                    Statement statement = connection.createStatement()) {
                connectionId = MySqlLiveFixture.scalar(connection, "SELECT CONNECTION_ID()");
                statement.execute("SET SESSION max_execution_time=1234");
                statement.execute("SET SESSION lock_wait_timeout=31");
                statement.execute("SET SESSION transaction_read_only=0");
            }
            MySqlLiveFixture.ObservedDataSource observed = new MySqlLiveFixture.ObservedDataSource(pool);
            observed.probeWrite = true;
            var service = MySqlLiveFixture.service(observed);
            assertThat(service.report().status()).isEqualTo("NOT_READ");
            assertThat(observed.borrows).isZero();
            var report = service.read();
            assertThat(report.dataSourcesRead()).isEqualTo(1);
            assertThat(observed.writeRejected)
                    .as("server error 1792 in actual READ ONLY transaction")
                    .isTrue();
            assertThat(observed.aborted).isFalse();
            try (Connection connection = pool.getConnection()) {
                assertThat(connection.getAutoCommit()).isTrue();
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT CONNECTION_ID()"))
                        .isEqualTo(connectionId);
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT @@session.max_execution_time"))
                        .isEqualTo("1234");
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT @@session.lock_wait_timeout"))
                        .isEqualTo("31");
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT @@session.transaction_read_only"))
                        .isEqualTo("0");
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT label FROM sample_orders WHERE id=1"))
                        .isEqualTo("synthetic-alpha");
            }
            int queries = observed.sql.size();
            assertThat(service.report()).isSameAs(report);
            assertThat(observed.sql).hasSize(queries);
        }
    }

    @Test
    void serverSelectTimeoutDoesNotNeedAnAuxiliaryKillConnectionAndPoolIsReusable() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader", "enableQueryTimeouts=false")) {
            MySqlLiveFixture.ObservedDataSource observed = new MySqlLiveFixture.ObservedDataSource(pool);
            observed.rewrite = sql -> sql.contains("FROM performance_schema.global_status")
                    ? "SELECT /*+ MAX_EXECUTION_TIME(50) */ SUM(SLEEP(0.05)) AS value"
                            + " FROM information_schema.columns LIMIT ?"
                    : sql;
            long started = System.nanoTime();
            var report = MySqlLiveFixture.service(observed).read();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(6));
            assertThat(report.status()).as("%s", report).isEqualTo("PARTIAL");
            assertThat(report.dataSources().get(0).sections()).anySatisfy(section -> {
                assertThat(section.id()).isEqualTo("vital-signs");
                assertThat(section.reason()).contains("timeout");
            });
            assertThat(observed.aborted).isFalse();
            try (Connection connection = pool.getConnection()) {
                assertThat(connection.getAutoCommit()).isTrue();
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT @@session.transaction_read_only"))
                        .isEqualTo("0");
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT 1")).isEqualTo("1");
            }
        }
    }

    @Test
    void restorationFailureDiscardsPhysicalConnectionBeforePoolReuse() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
            String before;
            try (Connection connection = pool.getConnection();
                    Statement statement = connection.createStatement()) {
                before = MySqlLiveFixture.scalar(connection, "SELECT CONNECTION_ID()");
                statement.execute("SET SESSION max_execution_time=1234");
            }
            MySqlLiveFixture.ObservedDataSource observed = new MySqlLiveFixture.ObservedDataSource(pool);
            observed.failRestore = true;
            var report = MySqlLiveFixture.service(observed).read();
            assertThat(observed.aborted).isTrue();
            assertThat(report.diagnostics())
                    .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("aborted and discarded"));
            try (Connection connection = pool.getConnection()) {
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT CONNECTION_ID()"))
                        .isNotEqualTo(before);
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT @@session.transaction_read_only"))
                        .isEqualTo("0");
            }
        }
    }

    @Test
    void manualCommitPoolDefaultIsRefusedWithoutSql() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader", "", false)) {
            MySqlLiveFixture.ObservedDataSource observed = new MySqlLiveFixture.ObservedDataSource(pool);
            var report = MySqlLiveFixture.service(observed).read();
            assertThat(report.status()).isEqualTo("ERROR");
            assertThat(report.dataSources().get(0).message()).contains("manual-commit");
            assertThat(observed.sql).isEmpty();
        }
    }

    @Test
    void exhaustedPoolIsBoundedByApplicationAcquisitionTimeoutNotAnAsyncBorrow() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader");
                Connection held = pool.getConnection()) {
            long started = System.nanoTime();
            var report = MySqlLiveFixture.service(pool).read();
            assertThat(report.status()).isEqualTo("ERROR");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(4));
            assertThat(held.isClosed()).isFalse();
        }
    }

    @Test
    void originalSqlReadOnlyModeIsRestoredEvenWhenJdbcHintPropagationIsDisabled() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader", "readOnlyPropagatesToServer=false")) {
            try (Connection connection = pool.getConnection();
                    Statement sql = connection.createStatement()) {
                sql.execute("SET SESSION transaction_read_only=1");
            }
            assertThat(MySqlLiveFixture.service(pool).read().dataSourcesRead()).isEqualTo(1);
            try (Connection connection = pool.getConnection()) {
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT @@session.transaction_read_only"))
                        .isEqualTo("1");
            }
        }
    }

    @Test
    void metadataLockTimeoutLeavesTheInspectionPoolReusable() throws Exception {
        try (Connection holder = java.sql.DriverManager.getConnection(
                        mysql.getJdbcUrl(), "application", MySqlLiveFixture.PASSWORD);
                Connection ddl = java.sql.DriverManager.getConnection(
                        mysql.getJdbcUrl(), "application", MySqlLiveFixture.PASSWORD);
                HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
            holder.setAutoCommit(false);
            try (Statement sql = holder.createStatement()) {
                sql.executeQuery("SELECT id FROM sample_audit").close();
            }
            CompletableFuture<Boolean> pending = CompletableFuture.supplyAsync(() -> {
                try (Statement sql = ddl.createStatement()) {
                    sql.execute("SET SESSION lock_wait_timeout=10");
                    sql.execute("ALTER TABLE sample_audit COMMENT='timeout-fixture'");
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            });
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> {
                    try (Connection admin = MySqlLiveFixture.admin(mysql)) {
                        return !"0"
                                .equals(MySqlLiveFixture.scalar(
                                        admin,
                                        "SELECT COUNT(*) FROM performance_schema.metadata_locks WHERE"
                                                + " LOCK_STATUS='PENDING'"));
                    }
                });
                MySqlLiveFixture.ObservedDataSource observed = new MySqlLiveFixture.ObservedDataSource(pool);
                // This deliberately blocked synthetic fixture SELECT is a test hook, not a production collector.
                observed.rewrite = sql -> sql.contains("FROM performance_schema.global_status")
                        ? "SELECT /*+ MAX_EXECUTION_TIME(5000) */ COUNT(*) AS value FROM sample_audit LIMIT ?"
                        : sql;
                var report = MySqlLiveFixture.service(observed).read();
                assertThat(report.dataSourcesRead()).isEqualTo(1);
                assertThat(report.dataSources().get(0).sections()).anySatisfy(section -> {
                    assertThat(section.id()).isEqualTo("vital-signs");
                    assertThat(section.reason()).contains("metadata-lock timeout");
                });
                try (Connection connection = pool.getConnection()) {
                    assertThat(connection.getAutoCommit()).isTrue();
                    assertThat(MySqlLiveFixture.scalar(connection, "SELECT @@session.transaction_read_only"))
                            .isEqualTo("0");
                }
            } finally {
                holder.rollback();
                pending.get(12, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void lostConnectionStopsSqlAndNextBorrowGetsAFreshPhysicalConnection() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
            MySqlLiveFixture.ObservedDataSource observed = new MySqlLiveFixture.ObservedDataSource(pool);
            String[] killed = {null};
            observed.beforeStatistics = connection -> {
                killed[0] = MySqlLiveFixture.scalar(connection, "SELECT CONNECTION_ID()");
                try (Connection admin = MySqlLiveFixture.admin(mysql);
                        Statement sql = admin.createStatement()) {
                    sql.execute("KILL CONNECTION " + Long.parseLong(killed[0]));
                }
            };
            assertThat(MySqlLiveFixture.service(observed).read().status()).isEqualTo("ERROR");
            assertThat(observed.sql)
                    .noneMatch(sql -> sql.contains("FROM performance_schema.threads")
                            || sql.contains("FROM information_schema.tables"));
            try (Connection connection = pool.getConnection()) {
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT CONNECTION_ID()"))
                        .isNotEqualTo(killed[0]);
                assertThat(MySqlLiveFixture.scalar(connection, "SELECT @@session.transaction_read_only"))
                        .isEqualTo("0");
            }
        }
    }

    @Test
    void unavailableServerDoesNotExposeConnectionPropertiesOrLeaveAnAsyncBorrow() throws Exception {
        try (var unresponsive = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:" + unresponsive.getLocalPort()
                            + "/synthetic?connectTimeout=500&socketTimeout=500",
                    "synthetic-reader",
                    "synthetic-sensitive");
            long started = System.nanoTime();
            var report = MySqlLiveFixture.service(source).read();
            assertThat(report.status()).isEqualTo("ERROR");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
            assertThat(report.toString()).doesNotContain("synthetic-sensitive", "jdbc:mysql", "socketTimeout");
        }
    }
}
