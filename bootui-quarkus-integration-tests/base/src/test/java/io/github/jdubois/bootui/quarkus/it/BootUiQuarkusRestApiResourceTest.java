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
 * Real-boot checks for the Quarkus REST API advisor ({@code RestApiResource} over the shared engine
 * {@code RestApiScanner}). Pins that the shared advisor models JAX-RS resources on Quarkus: a deliberately
 * imperfect {@code @Path} resource ({@code org.acme.restdemo.WidgetResource} — a {@code create}-style handler
 * mapped to GET) is imported via the bounded ArchUnit scan and fires {@code RAPI-MAP-003} (state-changing
 * handler on GET), exactly as on Spring.
 */
@QuarkusTest
class BootUiQuarkusRestApiResourceTest {

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void scanModelsTheJaxRsResourceAndFiresAStateChangingGetRule() {
        Response initial = probe().get("/bootui/api/rest-api");
        assertThat(initial.status()).as("GET /bootui/api/rest-api status").isEqualTo(200);
        assertThat(initial.isJson())
                .as("GET /bootui/api/rest-api content-type (%s)", initial.contentType())
                .isTrue();
        assertThat(initial.json().path("localOnly").asBoolean())
                .as("the advisor report must be flagged local-only")
                .isTrue();

        Response scan = probe().post("/bootui/api/rest-api/scan", JSON_HEADERS);
        assertThat(scan.status()).as("POST /bootui/api/rest-api/scan status").isEqualTo(200);
        JsonNode scanned = scan.json();
        assertThat(scanned.path("controllersAnalyzed").asInt())
                .as("the bounded import must model the JAX-RS resource")
                .isGreaterThan(0);
        assertThat(scanned.path("rulesEvaluated").asInt())
                .as("the shared REST best-practice ruleset must have run")
                .isGreaterThan(0);

        boolean stateChangingGetFailed = false;
        for (JsonNode result : scanned.path("results")) {
            if ("RAPI-MAP-003".equals(result.path("id").asText())
                    && "VIOLATION".equals(result.path("status").asText())
                    && result.path("violationCount").asInt() > 0) {
                stateChangingGetFailed = true;
            }
        }
        assertThat(stateChangingGetFailed)
                .as("a create-style JAX-RS handler mapped to GET must fail RAPI-MAP-003")
                .isTrue();
    }
}
