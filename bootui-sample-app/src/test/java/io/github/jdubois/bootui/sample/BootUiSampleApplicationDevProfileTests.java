package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Boots the sample app in the default, Docker-free {@code dev} profile (no
 * Testcontainers, no Docker Compose) and verifies the degraded experience works:
 * the in-memory H2 database backs JPA / Flyway / Liquibase, the simple cache
 * replaces Redis, BootUI is active, and the AI chat endpoint reports that AI is
 * unavailable instead of attempting a network call.
 *
 * <p>This is the default "try me" path (the published {@code jdubois/bootui-sample-app}
 * Docker image and the Playwright e2e suite run the {@code dev} profile), so it must
 * keep starting cleanly without any container engine. The full Docker experience lives in
 * the separate {@code docker} profile.
 */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-dev-test-overrides.properties"
        })
class BootUiSampleApplicationDevProfileTests {

    private static final Path OVERRIDES_FILE = Paths.get("target/bootui-dev-test-overrides.properties");

    @LocalServerPort
    int port;

    private RestClient client;

    @BeforeAll
    static void clearLeftoverOverridesFile() throws Exception {
        Files.deleteIfExists(OVERRIDES_FILE);
    }

    @AfterAll
    static void removeOverridesFile() throws Exception {
        Files.deleteIfExists(OVERRIDES_FILE);
    }

    @Test
    void servesSampleProductsFromInMemoryH2() {
        ResponseEntity<List> response =
                client().get().uri("/api/sample/products").retrieve().toEntity(List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // The products come from JPA (Hibernate ddl-auto) seeded by data.sql, proving H2 is wired in.
        assertThat(response.getBody().toString()).contains("BootUI Starter");
    }

    @Test
    void overviewReportsBootUiActiveWithoutDocker() {
        ResponseEntity<Map> response =
                client().get().uri("/bootui/api/overview").retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void healthIsUpWithoutRedisOrDatabaseDocker() {
        ResponseEntity<Map> response =
                client().get().uri("/actuator/health").retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    void chatReportsAiUnavailableWhenOllamaDisabled() {
        ResponseEntity<Map> response = client().post()
                .uri("/api/chat")
                .body(Map.of("message", "Hello"))
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).asString().contains("ChatClient");
    }

    private RestClient client() {
        if (client == null) {
            client = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .requestFactory(new JdkClientHttpRequestFactory(
                            HttpClient.newBuilder().proxy(new NoProxySelector()).build()))
                    // Never throw on non-2xx — tests inspect the status directly.
                    .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
                    .build();
        }
        return client;
    }

    private static final class NoProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {}
    }
}
