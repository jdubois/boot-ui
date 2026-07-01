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
 * Proves the Quarkus Health panel light-up end to end on an app that <strong>does</strong> have
 * {@code quarkus-smallrye-health} on its classpath: the custom {@link CustomLivenessCheck} is aggregated by
 * SmallRye, read in-process by {@code QuarkusHealthProvider} (registered by the extension's SmallRye-Health
 * capability build step), mapped onto the neutral {@code HealthNodeDto}, and surfaced on
 * {@code GET /bootui/api/health} by the shared engine {@code HealthService} — with no HTTP round trip to
 * {@code /q/health}.
 *
 * <p>This is the SmallRye-<em>present</em> half of the Health coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the SmallRye-<em>absent</em> path (the panel is still
 * available and renders setup guidance, with SmallRye never linked). The shared conformance suite already pins
 * that {@code GET /bootui/api/health} answers 200 + JSON on both adapters; this test pins the richer contract
 * that a real check's name, status and data flow through to the panel.</p>
 */
@QuarkusTest
class BootUiQuarkusHealthCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void healthPanelSurfacesSmallRyeChecks() {
        Response response = probe().get("/bootui/api/health");
        assertThat(response.status()).as("GET /bootui/api/health status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/health content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();
        assertThat(root.path("available").asBoolean(false))
                .as("with SmallRye Health present the panel reports the real report, not the DISABLED guidance root")
                .isTrue();
        assertThat(root.path("name").asText(null)).as("root node name").isEqualTo("application");
        assertThat(root.path("status").asText(null))
                .as("the aggregated status is UP (the only check is an UP @Liveness check)")
                .isEqualTo("UP");
        assertThat(isNullOrMissing(root.path("unavailableReason")))
                .as("an available health root carries no unavailableReason")
                .isTrue();
        assertThat(isNullOrMissing(root.path("guidanceReason")))
                .as("SmallRye has no framework-default contributors, so the default-only guidance never fires")
                .isTrue();
        assertThat(root.path("setup").isArray() && root.path("setup").isEmpty())
                .as("a present backend renders no setup steps")
                .isTrue();

        JsonNode liveness = null;
        for (JsonNode component : root.path("components")) {
            if (CustomLivenessCheck.CHECK_NAME.equals(component.path("name").asText(null))) {
                liveness = component;
            }
        }
        assertThat(liveness)
                .as("the custom @Liveness check must surface as a health component")
                .isNotNull();
        assertThat(liveness.path("status").asText(null)).as("the check status").isEqualTo("UP");
        assertThat(liveness.path("details").path("detail").asText(null))
                .as("the check's MicroProfile data must map onto the component details")
                .isEqualTo("alive");
        assertThat(liveness.path("components").isArray()
                        && liveness.path("components").isEmpty())
                .as("MicroProfile Health checks never nest")
                .isTrue();
    }

    private static boolean isNullOrMissing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }
}
