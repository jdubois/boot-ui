package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Pins the Quarkus-specific values of the shared shell-chrome endpoint
 * ({@code GET /bootui/api/overview}) that the cross-adapter conformance contract deliberately leaves
 * platform-tolerant: that the framework is reported as Quarkus, that the Quarkus version is resolved
 * (proving the build-time {@code bootui.internal.quarkus-version} capture reached runtime, where
 * {@code Package#getImplementationVersion()} would have returned {@code null}), and that BootUI reports
 * itself active whenever the endpoint can answer.
 *
 * <p>Also pins that the Overview dashboard panel is reported <em>available</em> in the panel manifest
 * on Quarkus, so the shared shell renders the dashboard rather than the "unavailable" banner. The
 * dashboard itself aggregates the advisor endpoints client-side; this endpoint supplies the shell
 * chrome the shell needs on every platform.</p>
 */
@QuarkusTest
class BootUiQuarkusOverviewResourceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void overviewReportsQuarkusFrameworkAndVersion() {
        Response response = probe().get("/bootui/api/overview");
        assertThat(response.status()).as("GET /bootui/api/overview status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/overview content-type (%s)", response.contentType())
                .isTrue();

        JsonNode overview = response.json();
        assertThat(overview.path("frameworkName").asText())
                .as("overview frameworkName on Quarkus")
                .isEqualTo("Quarkus");
        assertThat(overview.path("frameworkVersion").isTextual())
                .as("overview frameworkVersion must be resolved from the build-time capture")
                .isTrue();
        assertThat(overview.path("frameworkVersion").asText())
                .as("overview frameworkVersion must be non-blank")
                .isNotBlank();
        assertThat(overview.path("activeProfiles").isArray())
                .as("overview activeProfiles must be an array")
                .isTrue();
    }

    @Test
    void overviewReportsActiveActivationStatus() {
        Response response = probe().get("/bootui/api/overview");
        assertThat(response.status()).as("GET /bootui/api/overview status").isEqualTo(200);

        JsonNode activation = response.json().path("activation");
        assertThat(activation.path("enabled").asBoolean())
                .as("BootUI reports itself active whenever the overview endpoint answers (non-prod)")
                .isTrue();
        assertThat(activation.path("reason").asText())
                .as("activation reason is populated")
                .isNotBlank();
        assertThat(activation.path("localhostOnly").asBoolean(true))
                .as("Quarkus honestly reports localhost-only filtering is not yet fully enforced")
                .isFalse();
    }

    @Test
    void overviewPanelIsReportedAvailable() {
        Response response = probe().get("/bootui/api/panels");
        assertThat(response.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode overview = null;
        for (JsonNode panel : response.json().path("panels")) {
            if ("overview".equals(panel.path("id").asText(null))) {
                overview = panel;
                break;
            }
        }
        assertThat(overview)
                .as("overview panel is present in the Quarkus manifest")
                .isNotNull();
        assertThat(overview.path("available").asBoolean(false))
                .as("Overview dashboard panel is available on Quarkus (no 'unavailable' banner)")
                .isTrue();
        assertThat(overview.path("unavailableReason").isNull()
                        || overview.path("unavailableReason").asText().isEmpty())
                .as("an available Overview panel carries no unavailable reason")
                .isTrue();
    }
}
