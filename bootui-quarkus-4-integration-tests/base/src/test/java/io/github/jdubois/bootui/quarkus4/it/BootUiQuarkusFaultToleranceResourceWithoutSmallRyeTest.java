package io.github.jdubois.bootui.quarkus4.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Pins the Fault Tolerance panel's behavior on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-smallrye-fault-tolerance} on its classpath (this integration-test module deliberately omits
 * it).
 *
 * <p>This is the fault-tolerance-<em>absent</em> half of the panel coverage (the present capture path lives in
 * the {@code bootui-quarkus-fault-tolerance-integration-tests} module). It proves the capability gate fails closed:
 * with the {@code SMALLRYE_FAULT_TOLERANCE} capability absent, the deployment processor produces no synthetic
 * {@code QuarkusFaultTolerancePolicies} bean and excludes the only fault-tolerance-API-importing class from Arc, so
 * {@code GET /bootui/api/fault-tolerance} answers with valid JSON reporting {@code faultTolerancePresent=false} - the
 * application boots normally with no {@code NoClassDefFoundError} - and the panel is reported <em>unavailable</em>
 * in the manifest with an honest capability hint.</p>
 */
@QuarkusTest
class BootUiQuarkusFaultToleranceResourceWithoutSmallRyeTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void faultTolerancePanelIsUnavailableWithACapabilityHintWithoutFaultTolerance() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode faultTolerance = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("fault-tolerance".equals(panel.path("id").asText(null))) {
                faultTolerance = panel;
            }
        }
        assertThat(faultTolerance)
                .as("the Fault Tolerance panel is present in the manifest")
                .isNotNull();
        assertThat(faultTolerance.path("available").asBoolean(true))
                .as("the Fault Tolerance panel is unavailable when no fault-tolerance extension is present")
                .isFalse();
        assertThat(faultTolerance.path("unavailableReason").asText(null))
                .as("the unavailable reason names the extension to add, not the generic 'not yet' reason")
                .contains("quarkus-smallrye-fault-tolerance");
    }

    @Test
    void faultToleranceResourceRendersEmptyReportWithoutFaultTolerance() {
        Response response = probe().get("/bootui/api/fault-tolerance");
        assertThat(response.status())
                .as("GET /bootui/api/fault-tolerance status")
                .isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/fault-tolerance content-type (%s)", response.contentType())
                .isTrue();

        JsonNode body = response.json();
        assertThat(body.path("faultTolerancePresent").asBoolean(true))
                .as("with no fault-tolerance library the report is empty (faultTolerancePresent=false)")
                .isFalse();
        assertThat(body.path("totalPolicies").asInt(-1))
                .as("no policies without a fault-tolerance library")
                .isEqualTo(0);
        assertThat(body.path("events")).as("and no events either").isEmpty();
        assertThat(body.path("unavailableReason").asText(null))
                .as("the report states why it is empty instead of looking like a healthy zero")
                .isNotBlank();
    }
}
