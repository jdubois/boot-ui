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
 * Real-boot checks for the Quarkus Architecture (ArchUnit) advisor ({@code ArchitectureResource} over the
 * shared engine {@code ArchitectureScanner}, bounded to the application base packages discovered from the
 * build-time Jandex index by {@code QuarkusBasePackageProvider}). The antichain reduction is unit-tested in
 * {@code BasePackageRootsTest}; this test pins the Quarkus-specific end-to-end pipeline: that base packages
 * are discovered at build time (including this app's own {@code org.acme.archdemo} package — see
 * {@code SampleApplicationBean}), that {@code POST /scan} imports the application classes and runs the shared
 * rule registry, and that the shared dismissed-rules store round-trips through its own resource.
 */
@QuarkusTest
class BootUiQuarkusArchitectureResourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void scanDiscoversTheApplicationBasePackagesAndRunsTheRuleRegistry() {
        // A GET before any scan returns the local-only "not scanned" report; it never triggers a scan.
        Response initial = probe().get("/bootui/api/architecture");
        assertThat(initial.status()).as("GET /bootui/api/architecture status").isEqualTo(200);
        assertThat(initial.isJson())
                .as("GET /bootui/api/architecture content-type (%s)", initial.contentType())
                .isTrue();
        JsonNode initialBody = initial.json();
        assertThat(initialBody.path("localOnly").asBoolean())
                .as("the advisor report must be flagged local-only")
                .isTrue();
        assertThat(initialBody.path("scan").path("status").asText())
                .as("a GET before POST /scan must report NOT_SCANNED")
                .isEqualTo("NOT_SCANNED");
        assertThat(textValues(initialBody.path("basePackages")))
                .as("base packages are discovered at build time, before any scan runs")
                .contains("org.acme.archdemo");

        // POST /scan runs the curated ArchUnit ruleset against the discovered application classes.
        Response scan = probe().post("/bootui/api/architecture/scan", JSON_HEADERS);
        assertThat(scan.status())
                .as("POST /bootui/api/architecture/scan status")
                .isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("scan").path("status").asText())
                .as("after POST /scan the report must be SCANNED")
                .isEqualTo("SCANNED");
        assertThat(scanned.path("classesAnalyzed").asInt())
                .as("the bounded import must find application classes to analyse")
                .isGreaterThan(0);
        assertThat(scanned.path("rulesEvaluated").asInt())
                .as("the shared curated rule registry must have run")
                .isGreaterThan(0);
        assertThat(textValues(scanned.path("basePackages")))
                .as("the scanned report echoes the discovered application base packages")
                .contains("org.acme.archdemo");

        // The result is cached, so a subsequent GET reflects the scan without re-running it.
        Response cached = probe().get("/bootui/api/architecture");
        assertThat(cached.json().path("scan").path("status").asText())
                .as("the last report is cached across requests")
                .isEqualTo("SCANNED");
    }

    @Test
    void dismissedRulesRoundTripThroughTheirOwnResource() {
        String ruleId = "IT-ARCH-DISMISS";

        Response dismiss = probe().post("/bootui/api/dismissed-rules/" + ruleId, JSON_HEADERS);
        assertThat(dismiss.status()).as("POST dismiss status").isEqualTo(200);
        assertThat(textValues(dismiss.json().path("dismissed")))
                .as("dismissing a rule adds it to the persisted set")
                .contains(ruleId);

        Response list = probe().get("/bootui/api/dismissed-rules");
        assertThat(list.status()).as("GET dismissed-rules status").isEqualTo(200);
        assertThat(textValues(list.json().path("dismissed")))
                .as("the dismissed rule is read back from the store")
                .contains(ruleId);

        Response restore = probe().request("DELETE", "/bootui/api/dismissed-rules/" + ruleId, JSON_HEADERS, null);
        assertThat(restore.status()).as("DELETE restore status").isEqualTo(200);
        assertThat(textValues(restore.json().path("dismissed")))
                .as("restoring a rule removes it from the persisted set")
                .doesNotContain(ruleId);
    }

    private static List<String> textValues(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(node -> values.add(node.asText()));
        }
        return values;
    }
}
