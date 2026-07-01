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
 * Real-boot checks for the Quarkus-native Security advisor ({@code SecurityResource} over the shared engine
 * {@code QuarkusSecurityScanner}). The rule logic is unit-tested in {@code QuarkusSecurityScannerTest}; this
 * pins the end-to-end wiring: a plain Quarkus test app configures no auth, so {@code POST /scan} must surface
 * {@code QS-AUTH-001} ("no authentication mechanism"), proving the config-driven snapshot reached the engine.
 */
@QuarkusTest
class BootUiQuarkusSecurityResourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void scanEvaluatesTheQuarkusConfigAndCaches() {
        Response initial = probe().get("/bootui/api/security");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.isJson()).isTrue();
        assertThat(initial.json().path("localOnly").asBoolean()).isTrue();
        assertThat(initial.json().path("scan").path("status").asText()).isEqualTo("NOT_SCANNED");

        Response scan = probe().post("/bootui/api/security/scan", JSON_HEADERS);
        assertThat(scan.status()).isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("scan").path("status").asText()).isEqualTo("SCANNED");
        assertThat(scanned.path("rulesEvaluated").asInt()).isGreaterThan(0);
        assertThat(ruleIds(scanned))
                .as("a no-auth Quarkus app must flag QS-AUTH-001")
                .contains("QS-AUTH-001");

        Response cached = probe().get("/bootui/api/security");
        assertThat(cached.json().path("scan").path("status").asText()).isEqualTo("SCANNED");
    }

    private static List<String> ruleIds(JsonNode report) {
        List<String> ids = new ArrayList<>();
        report.path("results").forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }
}
