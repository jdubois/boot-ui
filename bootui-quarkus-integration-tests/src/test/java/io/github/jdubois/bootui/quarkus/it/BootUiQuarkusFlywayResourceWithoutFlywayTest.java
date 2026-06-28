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
 * Pins the Flyway panel's behavior on a Quarkus app that does <strong>not</strong> have {@code quarkus-flyway}
 * on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the flyway-<em>absent</em> half of the Flyway coverage (the flyway-present light-up path lives in
 * the dedicated {@code bootui-quarkus-flyway-integration-tests} module). It proves the R2 capability gate fails
 * closed: the {@code org.flywaydb.*}/{@code io.quarkus.flyway.*}-importing {@code BootUiFlywayProducer} is
 * excluded by the deployment build step when {@code Capability.FLYWAY} is absent, so no {@code FlywayProvider}
 * bean exists and the {@code flyway} panel is reported <em>unavailable</em> in the manifest with an honest
 * capability hint — while the Flyway-API-free engine {@code FlywayService} is still wired, so
 * {@code GET /bootui/api/flyway/migrations} answers with valid JSON reporting {@code flywayPresent:false} (no
 * {@code NoClassDefFoundError} from the absent backend).</p>
 */
@QuarkusTest
class BootUiQuarkusFlywayResourceWithoutFlywayTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void flywayPanelIsUnavailableWithACapabilityHintWithoutQuarkusFlyway() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode flyway = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("flyway".equals(panel.path("id").asText(null))) {
                flyway = panel;
            }
        }
        assertThat(flyway).as("the Flyway panel is present in the manifest").isNotNull();
        assertThat(flyway.path("available").asBoolean(true))
                .as("the Flyway panel is unavailable when quarkus-flyway is absent")
                .isFalse();
        assertThat(flyway.path("unavailableReason").asText(null))
                .as("the unavailable reason names the extension to add, not the generic 'not yet' reason")
                .contains("quarkus-flyway");
    }

    @Test
    void flywayReportRendersUnavailableWithoutQuarkusFlyway() {
        Response report = probe().get("/bootui/api/flyway/migrations");
        assertThat(report.status())
                .as("GET /bootui/api/flyway/migrations status")
                .isEqualTo(200);
        assertThat(report.isJson())
                .as("GET /bootui/api/flyway/migrations content-type (%s)", report.contentType())
                .isTrue();
        assertThat(report.json().path("flywayPresent").asBoolean(true))
                .as("the report is unavailable when no FlywayProvider bean is wired")
                .isFalse();
    }
}
