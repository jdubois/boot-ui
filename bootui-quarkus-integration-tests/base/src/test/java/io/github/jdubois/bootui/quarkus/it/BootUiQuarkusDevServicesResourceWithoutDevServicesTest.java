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
 * Pins the Dev Services + DevTools panels on a Quarkus app that started <strong>no</strong> Dev Services
 * (this Docker-free integration-test module triggers none). It proves the absence path: the Dev Services
 * panel is reported unavailable with an honest capability hint, its {@code GET} renders an empty report, the
 * unavailable log/restart actions decline with {@code 409}, and DevTools is permanently not applicable.
 */
@QuarkusTest
class BootUiQuarkusDevServicesResourceWithoutDevServicesTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void devServicesPanelIsUnavailableWithAHintWithoutDevServices() {
        JsonNode panel = panel("dev-services");
        assertThat(panel)
                .as("the Dev Services panel is present in the manifest")
                .isNotNull();
        assertThat(panel.path("available").asBoolean(true))
                .as("Dev Services panel is unavailable when no dev service started")
                .isFalse();
        assertThat(panel.path("unavailableReason").asText(null)).contains("Dev Services");
    }

    @Test
    void devToolsPanelIsNotApplicableOnQuarkus() {
        JsonNode panel = panel("devtools");
        assertThat(panel).as("the DevTools panel is present in the manifest").isNotNull();
        assertThat(panel.path("available").asBoolean(true)).isFalse();
        assertThat(panel.path("unavailableReason").asText(null)).contains("Not applicable on Quarkus");
    }

    @Test
    void devServicesResourceRendersEmptyReport() {
        Response response = probe().get("/bootui/api/dev-services");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        JsonNode body = response.json();
        assertThat(body.path("total").asInt(-1)).isZero();
        assertThat(body.path("dockerComposePresent").asBoolean(true)).isFalse();
        assertThat(body.path("testcontainersPresent").asBoolean(true)).isFalse();
    }

    @Test
    void logsAndRestartActionsDeclineWith409() {
        assertThat(probe().get("/bootui/api/dev-services/anything/logs").status())
                .isEqualTo(409);
        assertThat(probe().post("/bootui/api/dev-services/anything/restart", java.util.Map.of())
                        .status())
                .isEqualTo(409);
    }

    private JsonNode panel(String id) {
        for (JsonNode panel : probe().get("/bootui/api/panels").json().path("panels")) {
            if (id.equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        return null;
    }
}
