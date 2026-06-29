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
 * Pins the Scheduled Tasks panel's behavior on a Quarkus app that does <strong>not</strong> have
 * {@code quarkus-scheduler} on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the scheduler-<em>absent</em> half of the panel coverage (the scheduler-present capture path lives
 * in the {@code bootui-quarkus-scheduler-integration-tests} module). It proves the capability gate fails closed:
 * with the {@code SCHEDULER} capability absent, the deployment processor produces no synthetic
 * {@code QuarkusScheduledTasks} bean, so {@code QuarkusScheduledTaskProvider}'s {@code Instance} is unsatisfied —
 * {@code GET /bootui/api/scheduled} answers with valid JSON reporting {@code schedulingPresent=false}, and the
 * panel is reported <em>unavailable</em> in the manifest with an honest capability hint (its
 * {@code bootui.internal.scheduled-present} default stays {@code false}).</p>
 */
@QuarkusTest
class BootUiQuarkusScheduledResourceWithoutSchedulerTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void scheduledPanelIsUnavailableWithACapabilityHintWithoutScheduler() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode scheduled = null;
        for (JsonNode panel : panels.json().path("panels")) {
            if ("scheduled".equals(panel.path("id").asText(null))) {
                scheduled = panel;
            }
        }
        assertThat(scheduled)
                .as("the Scheduled Tasks panel is present in the manifest")
                .isNotNull();
        assertThat(scheduled.path("available").asBoolean(true))
                .as("the Scheduled Tasks panel is unavailable when quarkus-scheduler is absent")
                .isFalse();
        assertThat(scheduled.path("unavailableReason").asText(null))
                .as("the unavailable reason names the extension to add, not the generic 'not yet' reason")
                .contains("quarkus-scheduler");
    }

    @Test
    void scheduledResourceRendersEmptyReportWithoutScheduler() {
        Response response = probe().get("/bootui/api/scheduled");
        assertThat(response.status()).as("GET /bootui/api/scheduled status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/scheduled content-type (%s)", response.contentType())
                .isTrue();
        JsonNode body = response.json();
        assertThat(body.path("schedulingPresent").asBoolean(true))
                .as("with no scheduler the report is empty (schedulingPresent=false)")
                .isFalse();
        assertThat(body.path("total").asInt(-1))
                .as("no scheduled tasks without a scheduler")
                .isEqualTo(0);
    }
}
