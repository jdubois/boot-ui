package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NetworkClientsTests {

    @Test
    void aJdbcDriversFrameIsAnSqlClient() {
        NetworkClients.Client client = NetworkClients.recognize(
                "org.postgresql.core.PGStream#createSocket",
                "org.postgresql.core.PGStream#createSocket",
                null,
                "main",
                "db:5432");

        assertThat(client.label()).isEqualTo("PostgreSQL JDBC");
        assertThat(client.category()).isEqualTo(NetworkClients.SQL);
        assertThat(SideEffectsService.captureKey(client)).isEqualTo(SideEffectsStore.CAPTURE_SQL);
    }

    @Test
    void theJdksOwnClientsAreHttpClientsWhoseConnectsWaitForARestCall() {
        NetworkClients.Client client = NetworkClients.recognize(
                "jdk.internal.net.http.PlainHttpConnection#lambda$connectAsync$0",
                null,
                null,
                "HttpClient-{n}-Worker-{n}",
                "api.example.com:443");

        assertThat(client.label()).isEqualTo("JDK HttpClient");
        assertThat(SideEffectsService.captureKey(client)).isEqualTo(SideEffectsStore.REST_WAITING_HTTP);
        assertThat(NetworkClients.recognize("sun.net.NetworkClient#doConnect", null, null, "main", "x:80")
                        .label())
                .isEqualTo("JDK HttpURLConnection");
    }

    @Test
    void aNettyEventLoopsConnectIsRecognizedByItsThreadBeforeTheSharedTransport() {
        NetworkClients.Client lettuce = NetworkClients.recognize(
                null, "io.netty.util.internal.SocketUtils$3#run", null, "lettuce-nioEventLoop-{n}-{n}", "cache:6379");
        NetworkClients.Client netty =
                NetworkClients.recognize(null, "io.netty.util.internal.SocketUtils$3#run", null, "worker", "x:1");

        assertThat(lettuce.label()).isEqualTo("Lettuce");
        assertThat(netty.label()).isEqualTo("Netty");
    }

    @Test
    void messagingClientsCarryTheirBrokerAndInfrastructureIsNoPanels() {
        NetworkClients.Client kafka = NetworkClients.recognize(
                "org.apache.kafka.common.network.Selector#connect",
                null,
                null,
                "kafka-producer-network-thread",
                "broker:9092");
        NetworkClients.Client dns = NetworkClients.recognize(null, null, null, "worker", "10.0.0.2:53");

        assertThat(SideEffectsService.captureKey(kafka)).isEqualTo(SideEffectsStore.CAPTURE_MESSAGING + "kafka");
        assertThat(SideEffectsService.captureKey(dns)).isEqualTo(SideEffectsStore.CAPTURE_INFRASTRUCTURE);
        assertThat(SideEffectsService.captureKey(null)).isEqualTo(SideEffectsStore.REST_WAITING);
    }

    @Test
    void anApplicationsOwnSocketIsUnrecognized() {
        assertThat(NetworkClients.recognize(
                        "com.example.sdk.LicenseClient#check",
                        "com.example.sdk.LicenseClient#check",
                        "com.example.sdk.LicenseClient#check",
                        "http-nio-{n}-exec-{n}",
                        "localhost:8080"))
                .isNull();
    }
}
