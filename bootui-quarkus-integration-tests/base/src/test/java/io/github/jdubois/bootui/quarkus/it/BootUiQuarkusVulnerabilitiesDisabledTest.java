package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the operator kill-switch: with {@code bootui.vulnerabilities.osv-enabled=false} the
 * user-initiated {@code POST /bootui/api/vulnerabilities/scan} must short-circuit to a {@code DISABLED}
 * report without contacting OSV.dev (there is no stub here — a network call would fail the test), while the
 * local inventory GET still works and reports scanning as disabled. Mirrors the Spring adapter's behavior.
 */
@QuarkusTest
@TestProfile(BootUiQuarkusVulnerabilitiesDisabledTest.OsvDisabled.class)
class BootUiQuarkusVulnerabilitiesDisabledTest {

    public static class OsvDisabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.vulnerabilities.osv-enabled", "false");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void scanIsRefusedWhenOsvIsDisabled() {
        Response response = probe().request("POST", "/bootui/api/vulnerabilities/scan", Map.of(), "");
        assertThat(response.status()).as("POST /scan status").isEqualTo(200);
        assertThat(response.isJson()).isTrue();

        JsonNode body = response.json();
        assertThat(body.path("scan").path("status").asText())
                .as("a disabled scanner returns DISABLED, not a network attempt")
                .isEqualTo("DISABLED");
        assertThat(body.path("scanningEnabled").asBoolean()).isFalse();
    }

    @Test
    void inventoryStillLoadsWithScanningDisabled() {
        Response response = probe().get("/bootui/api/vulnerabilities");
        assertThat(response.status()).isEqualTo(200);

        JsonNode body = response.json();
        assertThat(body.path("scan").path("status").asText()).isEqualTo("NOT_SCANNED");
        assertThat(body.path("scanningEnabled").asBoolean())
                .as("the GET reflects the disabled scanner")
                .isFalse();
        assertThat(body.path("dependencies").size())
                .as("the local inventory is independent of OSV being enabled")
                .isGreaterThan(0);
    }
}
