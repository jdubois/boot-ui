package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.engine.mysql.MySqlRowLimits;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Every shipped collector is executed against Oracle MySQL, including real blocking and instrumentation. */
@Testcontainers(disabledWithoutDocker = true)
class MySqlCollectorsLiveTests {
    @Container
    static MySQLContainer mysql = MySqlLiveFixture.container();

    @BeforeAll
    static void initialize() throws Exception {
        MySqlLiveFixture.initialize(mysql);
        try (Connection admin = MySqlLiveFixture.admin(mysql);
                Statement sql = admin.createStatement()) {
            sql.execute("GRANT PROCESS ON *.* TO 'reader'@'%'");
            // Tests may enable instrumentation; production code never does.
            sql.execute("UPDATE performance_schema.setup_instruments SET ENABLED='YES',TIMED='YES'"
                    + " WHERE NAME LIKE 'statement/sql/%' OR NAME='wait/io/table/sql/handler'");
        }
    }

    @Test
    void everyCollectorWorksWithRestrictedTableGrantsAndOptionalProcess() throws Exception {
        try (Connection application = application();
                Statement workload = application.createStatement();
                HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
            workload.executeQuery("SELECT id FROM sample_orders WHERE label='synthetic-alpha'")
                    .close();
            var source = MySqlLiveFixture.service(pool).read().dataSources().get(0);
            assertThat(source.sections())
                    .hasSize(8)
                    .allSatisfy(section -> assertThat(section.status())
                            .as(section.id() + ": " + section.reason())
                            .isEqualTo("AVAILABLE"));
            assertThat(source.vitalSigns()).hasSize(17);
            assertThat(source.sessions()).isNotEmpty();
            assertThat(source.statements()).anySatisfy(statement -> {
                assertThat(statement.digestText()).contains("sample_orders");
                assertThat(statement.digestText()).doesNotContain("synthetic-alpha");
                assertThat(statement.totalTimeMs()).isNotNull();
            });
            assertThat(source.tables()).hasSize(2).allSatisfy(table -> {
                assertThat(table.estimatedRows()).isNotNull();
                assertThat(table.dataBytes()).isNotNull();
                assertThat(table.readOperations()).isNotNull();
            });
            assertThat(source.indexes()).isNotEmpty();
            assertThat(source.innodb()).extracting("id").contains("lock_deadlocks", "trx_rseg_history_len");
            assertThat(source.replication()).isEmpty();
            assertThat(source.settings()).hasSize(17);
        }
    }

    @Test
    void rowCapsNeedAnExtraRowAndStaySeparateFromUnknownInstrumentation() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
            var capped = MySqlLiveFixture.service(pool, new MySqlRowLimits(100, 100, 500, 1, 100, 10, 40))
                    .read();
            assertThat(capped.truncated()).isTrue();
            assertThat(capped.dataSources().get(0).tables()).hasSize(1);
            assertThat(capped.dataSources().get(0).message()).isNull();
            assertThat(capped.dataSources().get(0).sections())
                    .allSatisfy(section -> assertThat(section.reason()).isNull());
            var exact = MySqlLiveFixture.service(pool, new MySqlRowLimits(100, 100, 500, 2, 100, 10, 40))
                    .read();
            assertThat(exact.dataSources().get(0).sections()).anySatisfy(section -> {
                assertThat(section.id()).isEqualTo("tables");
                assertThat(section.truncated()).isFalse();
            });
        }
    }

    @Test
    void digestsRemainAvailableWhenOnlyThreadInstrumentationIsDisabled() throws Exception {
        try (Connection admin = MySqlLiveFixture.admin(mysql);
                Statement sql = admin.createStatement()) {
            sql.execute(
                    "UPDATE performance_schema.setup_consumers SET ENABLED='NO' WHERE NAME='thread_instrumentation'");
            try (Connection application = application();
                    Statement workload = application.createStatement();
                    HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
                workload.executeQuery("SELECT SUM(id) AS thread_independent_total FROM sample_orders")
                        .close();
                var source = MySqlLiveFixture.service(pool).read().dataSources().get(0);
                assertThat(source.sections()).anySatisfy(section -> {
                    assertThat(section.id()).isEqualTo("statements");
                    assertThat(section.status()).isEqualTo("AVAILABLE");
                });
                assertThat(source.statements())
                        .anyMatch(statement -> statement.digestText() != null
                                && statement.digestText().contains("thread_independent_total"));
            } finally {
                sql.execute(
                        "UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME='thread_instrumentation'");
            }
        }
    }

    @Test
    void disabledDigestsDoNotLookLikeAnEmptyWorkload() throws Exception {
        try (Connection admin = MySqlLiveFixture.admin(mysql);
                Statement sql = admin.createStatement()) {
            sql.execute("UPDATE performance_schema.setup_consumers SET ENABLED='NO' WHERE NAME='statements_digest'");
            try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
                var source = MySqlLiveFixture.service(pool).read().dataSources().get(0);
                assertThat(source.statements()).isEmpty();
                assertThat(source.sections()).anySatisfy(section -> {
                    assertThat(section.id()).isEqualTo("statements");
                    assertThat(section.status()).isEqualTo("SKIPPED");
                    assertThat(section.reason()).contains("disabled");
                });
            } finally {
                sql.execute(
                        "UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME='statements_digest'");
            }
        }
    }

    @Test
    void disabledTimingReturnsUnknownNotZeroLatency() throws Exception {
        try (Connection admin = MySqlLiveFixture.admin(mysql);
                Statement sql = admin.createStatement()) {
            sql.execute("UPDATE performance_schema.setup_instruments SET TIMED='NO' WHERE NAME='statement/sql/select'");
            try (HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
                var source = MySqlLiveFixture.service(pool).read().dataSources().get(0);
                assertThat(source.statements())
                        .isNotEmpty()
                        .allSatisfy(
                                statement -> assertThat(statement.totalTimeMs()).isNull());
                assertThat(source.sections()).anySatisfy(section -> {
                    assertThat(section.id()).isEqualTo("statements");
                    assertThat(section.reason()).contains("timing");
                });
            } finally {
                sql.execute("UPDATE performance_schema.setup_instruments SET TIMED='YES' WHERE"
                        + " NAME='statement/sql/select'");
            }
        }
    }

    @Test
    void sleepingTransactionAndRealRowLockEdgesRemainDistinctFromStateAge() throws Exception {
        try (Connection blocker = application();
                Connection waiting = application();
                HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
            blocker.setAutoCommit(false);
            waiting.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.executeUpdate("UPDATE sample_orders SET label='held-fixture' WHERE id=1");
            }
            String blockerId = MySqlLiveFixture.scalar(blocker, "SELECT CONNECTION_ID()");
            CompletableFuture<Boolean> pending = CompletableFuture.supplyAsync(() -> {
                try (Statement lock = waiting.createStatement()) {
                    lock.execute("SET SESSION innodb_lock_wait_timeout=10");
                    lock.executeUpdate("UPDATE sample_orders SET label='waiting-fixture' WHERE id=1");
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
                                        admin, "SELECT COUNT(*) FROM performance_schema.data_lock_waits"));
                    }
                });
                var source = MySqlLiveFixture.service(pool).read().dataSources().get(0);
                assertThat(source.lockWaits()).anySatisfy(edge -> {
                    assertThat(edge.kind()).isEqualTo("ROW");
                    assertThat(edge.blockingThreadId()).isNotNull();
                    assertThat(edge.objectName()).isEqualTo("sample_orders");
                });
                assertThat(source.sessions()).anySatisfy(session -> {
                    assertThat(session.connectionId()).isEqualTo(blockerId);
                    assertThat(session.command()).isEqualTo("Sleep");
                    assertThat(session.transactionAgeSeconds()).isNotNull();
                    assertThat(session.rowsLocked()).isNotEqualTo("0");
                });
            } finally {
                blocker.rollback();
                pending.get(12, TimeUnit.SECONDS);
                waiting.rollback();
            }
        }
    }

    @Test
    void pendingMetadataLocksAreNotPresentedAsInventedBlockingGraphs() throws Exception {
        try (Connection holder = application();
                Connection ddl = application();
                HikariDataSource pool = MySqlLiveFixture.pool(mysql, "reader")) {
            holder.setAutoCommit(false);
            try (Statement sql = holder.createStatement()) {
                sql.executeQuery("SELECT id FROM sample_audit").close();
            }
            CompletableFuture<Boolean> pending = CompletableFuture.supplyAsync(() -> {
                try (Statement sql = ddl.createStatement()) {
                    sql.execute("SET SESSION lock_wait_timeout=10");
                    sql.execute("ALTER TABLE sample_audit COMMENT='synthetic'");
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
                var source = MySqlLiveFixture.service(pool).read().dataSources().get(0);
                assertThat(source.lockWaits()).anySatisfy(edge -> {
                    assertThat(edge.kind()).isEqualTo("METADATA");
                    assertThat(edge.blockingThreadId()).isNull();
                    assertThat(edge.status()).isEqualTo("PENDING");
                });
            } finally {
                holder.rollback();
                pending.get(12, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void noSelectedSchemaAndCrossSchemaSqlDoNotInventSchemaAssociation() throws Exception {
        String noSchemaUrl = mysql.getJdbcUrl().replace("/bootui_fixture", "/");
        var unselected = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                noSchemaUrl, "reader", MySqlLiveFixture.PASSWORD);
        var unselectedReport =
                MySqlLiveFixture.service(unselected).read().dataSources().get(0);
        assertThat(unselectedReport.schemaName()).isNull();
        assertThat(unselectedReport.vitalSigns()).isNotEmpty();
        assertThat(unselectedReport.tables()).isEmpty();
        assertThat(unselectedReport.sections()).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("tables");
            assertThat(section.status()).isEqualTo("SKIPPED");
        });
        try (Connection application =
                        DriverManager.getConnection(noSchemaUrl, "application", MySqlLiveFixture.PASSWORD);
                Statement sql = application.createStatement();
                HikariDataSource selected = MySqlLiveFixture.pool(mysql, "reader")) {
            String connectionId = MySqlLiveFixture.scalar(application, "SELECT CONNECTION_ID()");
            sql.executeQuery("SELECT id AS cross_schema_observation FROM bootui_fixture.sample_orders")
                    .close();
            var report = MySqlLiveFixture.service(selected).read().dataSources().get(0);
            assertThat(report.sessions()).noneMatch(session -> connectionId.equals(session.connectionId()));
            assertThat(report.statements())
                    .noneMatch(statement -> statement.digestText() != null
                            && statement.digestText().contains("cross_schema_observation"));
        }
    }

    private static Connection application() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), "application", MySqlLiveFixture.PASSWORD);
    }
}
