package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.show-banner=false",
            "bootui.vulnerabilities.epss-enabled=false",
            "bootui.overrides-file=target/vulnerabilities-it/application-bootui.properties"
        })
class SpringVulnerabilitiesIntegrationTest {

    private static final String ADVISORY_ID = "GHSA-spring-it-0001";
    private static final Path DISMISSALS_FILE = Path.of("target/vulnerabilities-it/boot-ui.yml");
    private static final AtomicInteger QUERY_CALLS = new AtomicInteger();
    private static HttpServer osv;

    @LocalServerPort
    int port;

    private BootUiHttpProbe probe;

    @DynamicPropertySource
    static void vulnerabilityProperties(DynamicPropertyRegistry registry) {
        startOsvStub();
        registry.add(
                "bootui.vulnerabilities.osv-base-uri",
                () -> "http://127.0.0.1:" + osv.getAddress().getPort());
    }

    @BeforeAll
    static void clearDismissals() throws IOException {
        Files.deleteIfExists(DISMISSALS_FILE);
    }

    @AfterAll
    static void stopOsvStub() throws IOException {
        Files.deleteIfExists(DISMISSALS_FILE);
        if (osv != null) {
            osv.stop(0);
        }
    }

    @Test
    void supportsLocalInventoryExplicitScanCacheAndDismissalLifecycle() {
        JsonNode inventory = get("/bootui/api/vulnerabilities").json();

        assertThat(inventory.path("scan").path("status").asText()).isEqualTo("NOT_SCANNED");
        assertThat(inventory.path("dependencies").size()).isGreaterThan(0);
        assertThat(QUERY_CALLS).hasValue(0);

        JsonNode scanned = write("POST", "/bootui/api/vulnerabilities/scan").json();

        assertThat(scanned.path("scan").path("status").asText()).isIn("SCANNED", "PARTIAL");
        assertThat(scanned.path("scan").path("vulnerabilitiesFound").asInt()).isEqualTo(1);
        assertThat(scanned.path("vulnerable").asInt()).isEqualTo(1);
        assertThat(firstVulnerableDependency(scanned)
                        .path("vulnerabilities")
                        .get(0)
                        .path("id")
                        .asText())
                .isEqualTo(ADVISORY_ID);
        assertThat(QUERY_CALLS).hasValue(1);

        JsonNode cached = get("/bootui/api/vulnerabilities").json();

        assertThat(cached.path("scan").path("status").asText()).isIn("SCANNED", "PARTIAL");
        assertThat(cached.path("scan").path("vulnerabilitiesFound").asInt()).isEqualTo(1);
        assertThat(QUERY_CALLS).hasValue(1);

        JsonNode dependency = firstVulnerableDependency(cached);
        String packageName = dependency.path("packageName").asText();
        String dismissalKey = ADVISORY_ID + "::" + packageName;

        assertThat(write("POST", "/bootui/api/dismissed-rules/" + dismissalKey).status())
                .isEqualTo(200);
        JsonNode dismissed = get("/bootui/api/vulnerabilities").json();
        assertThat(findDependency(dismissed, packageName)
                        .path("vulnerabilityCount")
                        .asInt())
                .isZero();
        assertThat(findDependency(dismissed, packageName)
                        .path("vulnerabilities")
                        .get(0)
                        .path("dismissed")
                        .asBoolean())
                .isTrue();
        assertThat(dismissed.path("vulnerable").asInt()).isZero();

        assertThat(write("DELETE", "/bootui/api/dismissed-rules/" + dismissalKey)
                        .status())
                .isEqualTo(200);
        JsonNode restored = get("/bootui/api/vulnerabilities").json();
        assertThat(findDependency(restored, packageName)
                        .path("vulnerabilityCount")
                        .asInt())
                .isEqualTo(1);
        assertThat(restored.path("vulnerable").asInt()).isEqualTo(1);
    }

    private Response get(String path) {
        Response response = probe().get(path);
        assertThat(response.status()).isEqualTo(200);
        return response;
    }

    private Response write(String method, String path) {
        probe().get("/bootui/api/overview");
        String token = probe().cookie("XSRF-TOKEN").orElseThrow();
        return probe().request(method, path, Map.of("X-XSRF-TOKEN", token), "");
    }

    private BootUiHttpProbe probe() {
        if (probe == null) {
            probe = new BootUiHttpProbe("http://localhost:" + port);
        }
        return probe;
    }

    private static JsonNode firstVulnerableDependency(JsonNode body) {
        for (JsonNode dependency : body.path("dependencies")) {
            if (!dependency.path("vulnerabilities").isEmpty()) {
                return dependency;
            }
        }
        throw new AssertionError("No vulnerable dependency found");
    }

    private static JsonNode findDependency(JsonNode body, String packageName) {
        for (JsonNode dependency : body.path("dependencies")) {
            if (packageName.equals(dependency.path("packageName").asText())) {
                return dependency;
            }
        }
        throw new AssertionError("Dependency not found: " + packageName);
    }

    private static void startOsvStub() {
        try {
            osv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            osv.createContext("/v1/querybatch", exchange -> {
                QUERY_CALLS.incrementAndGet();
                respond(exchange, "{\"results\":[{\"vulns\":[{\"id\":\"" + ADVISORY_ID + "\"}]}]}");
            });
            osv.createContext(
                    "/v1/vulns/",
                    exchange -> respond(
                            exchange,
                            "{\"id\":\"" + ADVISORY_ID + "\",\"summary\":\"Spring integration advisory\","
                                    + "\"severity\":[{\"type\":\"CVSS_V3\",\"score\":"
                                    + "\"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H\"}]}"));
            osv.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start the OSV stub", ex);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
