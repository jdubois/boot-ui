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
 * Real-boot checks for the Quarkus GitHub dashboard panel ({@code GitHubResource} over the shared engine
 * {@code GitHubDashboardService}, wired by {@code BootUiEngineProducer} with a Jackson-2 {@code GitHubApiClient}).
 *
 * <p>This is the Docker-free ground truth for the two safety-critical guarantees the panel must keep on
 * Quarkus, at parity with Spring: (1) {@code GET /bootui/api/github} renders the dashboard without ever calling
 * the network (the Jackson-2 parsing fidelity itself is pinned by the runtime {@code GitHubApiClientTest}); and
 * (2) the only state-changing endpoint, {@code POST /bootui/api/github/refresh}, honors
 * {@code bootui.github.api-enabled=false} by returning a {@code DISABLED} report and making no GitHub API call.
 * Both run with the API disabled so the test can never reach out to github.com regardless of ambient
 * credentials. The shared conformance suite separately auto-asserts the {@code GET} 200 in the default
 * (api-enabled) profile once the panel reports available.</p>
 */
@QuarkusTest
@TestProfile(BootUiQuarkusGitHubResourceTest.ApiDisabledProfile.class)
class BootUiQuarkusGitHubResourceTest {

    public static final class ApiDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.github.api-enabled", "false");
        }
    }

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void getRendersTheDashboardWithoutCallingTheNetwork() {
        Response response = probe().get("/bootui/api/github");

        assertThat(response.status()).as("GET /bootui/api/github status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/github content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        // The integration-tests module is itself a checkout of jdubois/boot-ui, so the engine detects the repo
        // and renders a render-time (network-free) dashboard. Depending on test ordering the cached report is
        // either the initial READY or the DISABLED produced by the api-disabled refresh test — both are
        // render-time states; the invariant is that GET never reports a live connection and never errors.
        assertThat(body.path("status").asText())
                .as("GET must render a render-time (non-network) status")
                .isIn("READY", "DISABLED");
        assertThat(body.path("connected").asBoolean())
                .as("render must never report a live connection")
                .isFalse();
        assertThat(body.path("repository").path("fullName").asText()).isEqualTo("jdubois/boot-ui");
    }

    @Test
    void refreshHonorsApiDisabledAndMakesNoNetworkCall() {
        Response response = probe().request("POST", "/bootui/api/github/refresh", JSON_HEADERS, "");

        assertThat(response.status()).as("POST /refresh status").isEqualTo(200);
        assertThat(response.isJson())
                .as("POST /refresh content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.path("status").asText())
                .as("with bootui.github.api-enabled=false the refresh action must short-circuit to DISABLED")
                .isEqualTo("DISABLED");
        assertThat(body.path("connected").asBoolean()).isFalse();
        assertThat(body.path("message").asText())
                .as("the DISABLED message must point the operator at the enabling flag")
                .contains("bootui.github.api-enabled=true");
    }
}
