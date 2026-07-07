package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.engine.constellation.PeerSnapshot;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConstellationHttpPeerClientTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchReadsIdentityFromOverviewAndPlatformFromPanels() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        json(
                "/bootui/api/overview",
                """
                {"applicationName":"orders-service","frameworkName":"Spring Boot","frameworkVersion":"4.1.0",
                 "javaVersion":"17","activeProfiles":["dev"]}
                """);
        json("/bootui/api/panels", """
                {"platform":"quarkus","panels":[]}
                """);
        server.start();

        ConstellationHttpPeerClient client = new ConstellationHttpPeerClient();
        PeerSnapshot snapshot = client.fetch(peerUrl(), Duration.ofSeconds(2));

        assertThat(snapshot.reachable()).isTrue();
        assertThat(snapshot.applicationName()).isEqualTo("orders-service");
        // the panels endpoint's platform discriminator wins over overview's frameworkName
        assertThat(snapshot.platform()).isEqualTo("quarkus");
        assertThat(snapshot.frameworkVersion()).isEqualTo("4.1.0");
        assertThat(snapshot.javaVersion()).isEqualTo("17");
        assertThat(snapshot.activeProfiles()).containsExactly("dev");
    }

    @Test
    void fetchDegradesGracefullyWhenPanelsEndpointIsMissingOrOlder() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        json(
                "/bootui/api/overview",
                """
                {"applicationName":"legacy-service","frameworkName":"Spring Boot","frameworkVersion":"3.9.0",
                 "javaVersion":"17","activeProfiles":[]}
                """);
        server.createContext("/bootui/api/panels", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        ConstellationHttpPeerClient client = new ConstellationHttpPeerClient();
        PeerSnapshot snapshot = client.fetch(peerUrl(), Duration.ofSeconds(2));

        assertThat(snapshot.reachable()).isTrue();
        assertThat(snapshot.applicationName()).isEqualTo("legacy-service");
        // falls back to overview's frameworkName when /panels is unreachable/older
        assertThat(snapshot.platform()).isEqualTo("Spring Boot");
    }

    @Test
    void fetchReturnsUnreachableWhenPeerRefusesConnection() {
        ConstellationHttpPeerClient client = new ConstellationHttpPeerClient();

        PeerSnapshot snapshot = client.fetch("http://localhost:1", Duration.ofMillis(500));

        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.errorMessage()).isNotBlank();
    }

    @Test
    void fetchRejectsNonLoopbackUrls() {
        ConstellationHttpPeerClient client = new ConstellationHttpPeerClient();

        PeerSnapshot snapshot = client.fetch("http://example.com:8080", Duration.ofSeconds(2));

        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.errorMessage()).contains("loopback");
    }

    private String peerUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void json(String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
