package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Pins the Health panel's behavior on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-smallrye-health} on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the SmallRye-<em>absent</em> half of the Health coverage (the SmallRye-present capture path lives
 * in the sibling {@code bootui-quarkus-health-integration-tests} module). It proves the R2 safety guarantee:
 * the engine {@code HealthService} is wired unconditionally (it holds no SmallRye types), so the panel is
 * available and {@code GET /bootui/api/health} answers with valid JSON, while the SmallRye-importing
 * {@code BootUiHealthProducer} is never registered — the app boots clean with no {@code NoClassDefFoundError}
 * from the absent backend. With no {@code HealthProvider} bean the service renders the DISABLED root carrying
 * the Quarkus SmallRye setup guidance, which the shared conformance suite (a shape-only GET) does not pin.</p>
 */
@QuarkusTest
class BootUiQuarkusHealthResourceWithoutSmallRyeTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void healthPanelRendersSetupGuidanceWithoutSmallRye() {
        Response response = probe().get("/bootui/api/health");
        assertThat(response.status()).as("GET /bootui/api/health status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/health content-type (%s)", response.contentType())
                .isTrue();

        assertThat(response.json().path("status").asText(null))
                .as("with no SmallRye Health backend the engine renders the DISABLED root")
                .isEqualTo("DISABLED");
        assertThat(response.json().path("available").asBoolean(true))
                .as("the health data root reports unavailable when no backend is present")
                .isFalse();
        assertThat(response.json().path("unavailableReason").asText(null))
                .as("the unavailable reason names SmallRye Health so the operator knows what to add")
                .contains("SmallRye Health");
        assertThat(response.json().path("setup").isArray()
                        && !response.json().path("setup").isEmpty())
                .as("the DISABLED root carries non-empty setup guidance")
                .isTrue();
    }

    @Test
    void healthPanelIsReportedAvailableInTheManifest() {
        // The Health *panel* is always available on Quarkus (it renders guidance when the backend is absent),
        // mirroring the Loggers/Traces "always available, render guidance/empty" stance.
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        boolean healthAvailable = false;
        for (var panel : panels.json().path("panels")) {
            if ("health".equals(panel.path("id").asText(null))) {
                healthAvailable = panel.path("available").asBoolean(false);
            }
        }
        assertThat(healthAvailable)
                .as("the Health panel is always available on Quarkus, even without SmallRye Health")
                .isTrue();
    }
}
