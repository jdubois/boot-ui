package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.core.dto.MySqlDataSourceDto;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class MySqlInstrumentationLiveTests {
    @Container
    static final MySQLContainer MYSQL = MySqlLiveFixture.container();

    @BeforeAll
    static void initialize() throws Exception {
        MySqlLiveFixture.initialize(MYSQL);
        try (Connection admin = MySqlLiveFixture.admin(MYSQL);
                Statement sql = admin.createStatement()) {
            sql.execute("GRANT PROCESS ON *.* TO 'reader'@'%'");
            sql.execute("UPDATE performance_schema.setup_instruments SET ENABLED='YES',TIMED='YES'"
                    + " WHERE NAME LIKE 'statement/sql/%' OR NAME IN"
                    + " ('wait/io/table/sql/handler','wait/lock/metadata/sql/mdl')");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"global-off", "handler-off", "handler-untimed", "object-off", "object-untimed"})
    void actualTableControlsDoNotProduceFalseZeroCountersOrTimings(String scenario) throws Exception {
        try (Connection admin = MySqlLiveFixture.admin(MYSQL);
                Statement sql = admin.createStatement()) {
            if (scenario.equals("global-off")) {
                sql.execute("UPDATE performance_schema.setup_consumers SET ENABLED='NO'"
                        + " WHERE NAME='global_instrumentation'");
            } else if (scenario.startsWith("handler-")) {
                sql.execute("UPDATE performance_schema.setup_instruments SET ENABLED='"
                        + (scenario.equals("handler-off") ? "NO" : "YES")
                        + "',TIMED='NO' WHERE NAME='wait/io/table/sql/handler'");
            } else {
                sql.execute("INSERT INTO performance_schema.setup_objects"
                        + " (OBJECT_TYPE,OBJECT_SCHEMA,OBJECT_NAME,ENABLED,TIMED)"
                        + " VALUES ('TABLE','bootui_fixture','sample_orders','"
                        + (scenario.equals("object-off") ? "NO" : "YES") + "','NO')");
            }
            try (Connection application = application();
                    Statement workload = application.createStatement();
                    HikariDataSource pool = MySqlLiveFixture.pool(MYSQL, "reader")) {
                workload.executeQuery("SELECT id FROM sample_orders").close();
                MySqlDataSourceDto report =
                        MySqlLiveFixture.service(pool).read().dataSources().get(0);
                boolean untimed = scenario.endsWith("untimed");
                assertThat(report.tables())
                        .filteredOn(table -> table.tableName().equals("sample_orders"))
                        .singleElement()
                        .satisfies(table -> {
                            assertThat(table.dataBytes()).isNotNull();
                            assertThat(table.totalTimeMs()).isNull();
                            if (untimed) {
                                assertThat(table.readOperations()).isNotNull();
                            } else {
                                assertThat(table.readOperations()).isNull();
                                assertThat(table.writeOperations()).isNull();
                            }
                        });
                assertThat(report.indexes())
                        .filteredOn(index -> index.tableName().equals("sample_orders"))
                        .allSatisfy(index -> {
                            assertThat(index.totalTimeMs()).isNull();
                            if (untimed) {
                                assertThat(index.readOperations()).isNotNull();
                            } else {
                                assertThat(index.readOperations()).isNull();
                            }
                        });
                assertThat(report.sections())
                        .filteredOn(section -> List.of("tables", "indexes").contains(section.id()))
                        .allSatisfy(section -> {
                            assertThat(section.status()).isEqualTo("AVAILABLE");
                            assertThat(section.reason())
                                    .contains(untimed ? "Table I/O timing" : "Table I/O collection");
                        });
            } finally {
                sql.execute("UPDATE performance_schema.setup_consumers SET ENABLED='YES'"
                        + " WHERE NAME='global_instrumentation'");
                sql.execute("UPDATE performance_schema.setup_instruments SET ENABLED='YES',TIMED='YES'"
                        + " WHERE NAME='wait/io/table/sql/handler'");
                sql.execute("DELETE FROM performance_schema.setup_objects WHERE OBJECT_TYPE='TABLE'"
                        + " AND OBJECT_SCHEMA='bootui_fixture' AND OBJECT_NAME='sample_orders'");
            }
        }
    }

    @Test
    void deniedObjectConfigurationRetainsCatalogDataWithUnknownActivity() throws Exception {
        try (Connection admin = MySqlLiveFixture.admin(MYSQL);
                Statement sql = admin.createStatement()) {
            sql.execute("REVOKE SELECT ON performance_schema.setup_objects FROM 'reader'@'%'");
            try (HikariDataSource pool = MySqlLiveFixture.pool(MYSQL, "reader")) {
                var report = MySqlLiveFixture.service(pool).read().dataSources().get(0);
                assertThat(report.tables()).isNotEmpty().allSatisfy(table -> {
                    assertThat(table.dataBytes()).isNotNull();
                    assertThat(table.readOperations()).isNull();
                    assertThat(table.totalTimeMs()).isNull();
                });
                assertThat(report.capabilities()).anySatisfy(capability -> {
                    assertThat(capability.source()).isEqualTo("performance_schema.setup_objects");
                    assertThat(capability.readability()).isEqualTo("DENIED");
                });
                assertThat(report.sections())
                        .filteredOn(section -> List.of("tables", "indexes").contains(section.id()))
                        .allSatisfy(section -> assertThat(section.reason()).contains("setup_objects", "cannot read"));
            } finally {
                sql.execute("GRANT SELECT ON performance_schema.setup_objects TO 'reader'@'%'");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingMetadataLocksCannotLookAbsentWhenInstrumentationIsDisabledOrUnknown(boolean denied) throws Exception {
        try (Connection admin = MySqlLiveFixture.admin(MYSQL);
                Statement configuration = admin.createStatement();
                Connection holder = application();
                Connection waiting = application()) {
            if (denied) {
                configuration.execute("REVOKE SELECT ON performance_schema.setup_instruments FROM 'reader'@'%'");
            } else {
                configuration.execute("UPDATE performance_schema.setup_instruments SET ENABLED='NO'"
                        + " WHERE NAME='wait/lock/metadata/sql/mdl'");
            }
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM sample_orders LIMIT 1").close();
            }
            String waitingId = MySqlLiveFixture.scalar(waiting, "SELECT CONNECTION_ID()");
            CompletableFuture<Void> pending = CompletableFuture.runAsync(() -> {
                try (Statement statement = waiting.createStatement()) {
                    statement.execute("SET SESSION lock_wait_timeout=30");
                    statement.execute("ALTER TABLE sample_orders ADD COLUMN metadata_audit_probe INT");
                } catch (SQLException failure) {
                    throw new CompletionException(failure);
                }
            });
            try (HikariDataSource pool = MySqlLiveFixture.pool(MYSQL, "reader")) {
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> "Waiting for table metadata lock"
                                .equals(MySqlLiveFixture.scalar(
                                        admin,
                                        "SELECT PROCESSLIST_STATE FROM performance_schema.threads"
                                                + " WHERE PROCESSLIST_ID=" + waitingId)));
                var report = MySqlLiveFixture.service(pool).read().dataSources().get(0);
                assertThat(report.sessions()).isNotEmpty();
                assertThat(report.sections()).anySatisfy(section -> {
                    assertThat(section.id()).isEqualTo("sessions");
                    assertThat(section.status()).isEqualTo("AVAILABLE");
                    assertThat(section.reason())
                            .contains("Metadata-lock instrumentation is " + (denied ? "unknown" : "disabled"));
                });
                if (!denied) {
                    assertThat(report.lockWaits()).noneMatch(lock -> lock.kind().equals("METADATA"));
                }
            } finally {
                holder.rollback();
                try {
                    pending.get(35, TimeUnit.SECONDS);
                    configuration.execute("ALTER TABLE sample_orders DROP COLUMN metadata_audit_probe");
                } finally {
                    configuration.execute("GRANT SELECT ON performance_schema.setup_instruments TO 'reader'@'%'");
                    configuration.execute("UPDATE performance_schema.setup_instruments SET ENABLED='YES',TIMED='YES'"
                            + " WHERE NAME='wait/lock/metadata/sql/mdl'");
                }
            }
        }
    }

    private static Connection application() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
