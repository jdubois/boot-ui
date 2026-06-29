package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus-native application advisor ({@code SpringResource} over the shared engine
 * {@code QuarkusAppScanner}). The rule logic is unit-tested in {@code QuarkusAppScannerTest}; this pins the
 * end-to-end wiring: the test app uses no {@code @ConfigProperty}, so {@code POST /scan} must surface a
 * {@code QA-*} rule, proving the build-time idiom counts reached the engine via config.
 */
@QuarkusTest
class BootUiQuarkusSpringResourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void scanEvaluatesTheQuarkusIdiomsAndCaches() {
        Response initial = probe().get("/bootui/api/spring");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.isJson()).isTrue();
        assertThat(initial.json().path("localOnly").asBoolean()).isTrue();
        assertThat(initial.json().path("scan").path("status").asText()).isEqualTo("NOT_SCANNED");

        Response scan = probe().post("/bootui/api/spring/scan", JSON_HEADERS);
        assertThat(scan.status()).isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("scan").path("status").asText()).isEqualTo("SCANNED");
        assertThat(scanned.path("rulesEvaluated").asInt()).isGreaterThan(0);
        assertThat(ruleIds(scanned))
                .as("any application advisor finding must be a Quarkus QA-* rule, never a Spring one")
                .allMatch(id -> id.startsWith("QA-"));

        Response cached = probe().get("/bootui/api/spring");
        assertThat(cached.json().path("scan").path("status").asText()).isEqualTo("SCANNED");
    }

    private static List<String> ruleIds(JsonNode report) {
        List<String> ids = new ArrayList<>();
        report.path("results").forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }
}
