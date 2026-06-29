package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus Memory advisor ({@code MemoryResource} over the shared engine
 * {@code MemoryScanner}). The collection and rule logic is unit-tested in {@code MemoryScannerTests}/
 * {@code MemoryRulesTests} in {@code bootui-engine}; this test pins the Quarkus-specific end-to-end
 * pipeline: that a {@code GET} before any scan returns the local-only "not scanned" report without
 * running, that {@code POST /scan} aggregates the live JMX runtime and runs the curated rule registry,
 * and that the result is cached across requests. The advisor needs no Quarkus extension — it reads only
 * management beans present on every JVM — so it is always available.
 */
@QuarkusTest
class BootUiQuarkusMemoryResourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void scanAggregatesTheLiveJvmRuntimeAndRunsTheRuleRegistry() {
        // A GET before any scan returns the local-only "not scanned" report; it never triggers a scan.
        Response initial = probe().get("/bootui/api/memory");
        assertThat(initial.status()).as("GET /bootui/api/memory status").isEqualTo(200);
        assertThat(initial.isJson())
                .as("GET /bootui/api/memory content-type (%s)", initial.contentType())
                .isTrue();
        JsonNode initialBody = initial.json();
        assertThat(initialBody.path("localOnly").asBoolean())
                .as("the advisor report must be flagged local-only")
                .isTrue();
        assertThat(initialBody.path("scan").path("status").asText())
                .as("a GET before POST /scan must report NOT_SCANNED")
                .isEqualTo("NOT_SCANNED");

        // POST /scan reads the live JMX management beans and runs the curated rule registry.
        Response scan = probe().post("/bootui/api/memory/scan", JSON_HEADERS);
        assertThat(scan.status()).as("POST /bootui/api/memory/scan status").isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("scan").path("status").asText())
                .as("after POST /scan the report must be SCANNED or PARTIAL")
                .isIn("SCANNED", "PARTIAL");
        assertThat(scanned.path("rulesEvaluated").asInt())
                .as("the shared curated rule registry must have run")
                .isGreaterThan(0);

        // The result is cached, so a subsequent GET reflects the scan without re-running it.
        Response cached = probe().get("/bootui/api/memory");
        assertThat(cached.json().path("scan").path("status").asText())
                .as("the last report is cached across requests")
                .isIn("SCANNED", "PARTIAL");
    }
}
