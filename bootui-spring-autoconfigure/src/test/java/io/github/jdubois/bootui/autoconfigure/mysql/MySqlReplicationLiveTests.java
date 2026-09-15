package io.github.jdubois.bootui.autoconfigure.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** A genuine local source/replica channel; fixtures alone may administer replication. */
@Testcontainers(disabledWithoutDocker = true)
class MySqlReplicationLiveTests {
    static Network network = Network.newNetwork();

    @Container
    static MySQLContainer source = MySqlLiveFixture.container()
            .withNetwork(network)
            .withNetworkAliases("bootui-mysql-source")
            .withCommand("--performance-schema=ON", "--server-id=101");

    @Container
    static MySQLContainer replica =
            MySqlLiveFixture.container().withNetwork(network).withCommand("--performance-schema=ON", "--server-id=102");

    @Container
    static MySQLContainer uninstrumentedReplica = MySqlLiveFixture.container()
            .withNetwork(network)
            .withCommand("--performance-schema=OFF", "--server-id=103");

    @BeforeAll
    static void initialize() throws Exception {
        MySqlLiveFixture.initialize(source);
        MySqlLiveFixture.initialize(replica);
        MySqlLiveFixture.initialize(uninstrumentedReplica);
        String file;
        String position;
        try (Connection admin = MySqlLiveFixture.admin(source);
                Statement sql = admin.createStatement()) {
            sql.execute("CREATE USER 'replicator'@'%' IDENTIFIED BY 'synthetic-replication-only'");
            sql.execute("GRANT REPLICATION SLAVE ON *.* TO 'replicator'@'%'");
            try (var rows = sql.executeQuery("SHOW BINARY LOG STATUS")) {
                rows.next();
                file = rows.getString("File");
                position = rows.getString("Position");
            }
        }
        for (MySQLContainer target : java.util.List.of(replica, uninstrumentedReplica)) {
            try (Connection admin = MySqlLiveFixture.admin(target);
                    Statement sql = admin.createStatement()) {
                sql.execute("CHANGE REPLICATION SOURCE TO SOURCE_HOST='bootui-mysql-source',SOURCE_PORT=3306,"
                        + "SOURCE_USER='replicator',SOURCE_PASSWORD='synthetic-replication-only',"
                        + "SOURCE_LOG_FILE='" + file + "',SOURCE_LOG_POS=" + position
                        + ",GET_SOURCE_PUBLIC_KEY=1,SOURCE_CONNECT_RETRY=1 FOR CHANNEL 'bootui_fixture_channel'");
                sql.execute("START REPLICA FOR CHANNEL 'bootui_fixture_channel'");
            }
        }
    }

    @AfterAll
    static void cleanupNetwork() {
        replica.stop();
        uninstrumentedReplica.stop();
        source.stop();
        network.close();
    }

    @Test
    void replicationRemainsObservableWhenPerformanceSchemaIsDisabled() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(uninstrumentedReplica, "reader")) {
            var service = MySqlLiveFixture.service(pool);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var report = service.read().dataSources().get(0);
                assertThat(report.replication()).singleElement().satisfies(channel -> {
                    assertThat(channel.receiverState()).isEqualTo("ON");
                    assertThat(channel.applierState()).isEqualTo("ON");
                    assertThat(channel.lastErrorNumber()).isZero();
                });
                assertThat(report.sections()).anySatisfy(section -> {
                    assertThat(section.id()).isEqualTo("replication");
                    assertThat(section.status()).isEqualTo("AVAILABLE");
                    assertThat(section.reason()).isNull();
                });
            });
        }
    }

    @Test
    void runningStoppedAndErroredChannelsRetainIndependentLocalEvidence() throws Exception {
        try (HikariDataSource pool = MySqlLiveFixture.pool(replica, "reader")) {
            var service = MySqlLiveFixture.service(pool);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var channels = service.read().dataSources().get(0).replication();
                assertThat(channels).singleElement().satisfies(channel -> {
                    assertThat(channel.receiverState()).isEqualTo("ON");
                    assertThat(channel.applierState()).isEqualTo("ON");
                    assertThat(channel.lastErrorNumber()).isZero();
                    assertThat(channel.workerCount()).isNotNull();
                });
            });
            try (Connection admin = MySqlLiveFixture.admin(replica);
                    Statement sql = admin.createStatement()) {
                sql.execute("STOP REPLICA FOR CHANNEL 'bootui_fixture_channel'");
                assertThat(service.read().dataSources().get(0).replication())
                        .singleElement()
                        .satisfies(channel -> {
                            assertThat(channel.receiverState()).isEqualTo("OFF");
                            assertThat(channel.applierState()).isEqualTo("OFF");
                        });
                sql.execute("CHANGE REPLICATION SOURCE TO SOURCE_PASSWORD='synthetic-wrong-password'"
                        + " FOR CHANNEL 'bootui_fixture_channel'");
                sql.execute("START REPLICA FOR CHANNEL 'bootui_fixture_channel'");
                try {
                    await().atMost(Duration.ofSeconds(15))
                            .untilAsserted(() -> assertThat(
                                            service.read().dataSources().get(0).replication())
                                    .singleElement()
                                    .satisfies(channel -> assertThat(channel.lastErrorNumber())
                                            .isPositive()));
                } finally {
                    sql.execute("STOP REPLICA FOR CHANNEL 'bootui_fixture_channel'");
                }
            }
        }
    }
}
