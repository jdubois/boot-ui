package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Pins the Metrics panel's behavior on a Quarkus app that does <strong>not</strong> have a
 * {@code quarkus-micrometer} registry on its classpath (this integration-test module deliberately omits it).
 *
 * <p>This is the Micrometer-<em>absent</em> half of the Metrics coverage (the Micrometer-present capture path
 * lives in the sibling {@code bootui-quarkus-micrometer-integration-tests} module). Micrometer's API is always
 * on the classpath (it is a sanctioned {@code bootui-engine} dependency), but with no registry extension there
 * is no {@code MeterRegistry} bean: the engine {@code MetricsReportProvider} resolves a {@code null} registry
 * through the {@code Instance} handle and renders the report as unavailable. The Metrics <em>panel</em> stays
 * available in the manifest (it renders the unavailable state), mirroring the Health/Traces "always available,
 * render guidance/empty" stance.</p>
 */
@QuarkusTest
class BootUiQuarkusMetricsResourceWithoutMicrometerTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void metricsPanelReportsUnavailableWithoutMicrometer() {
        Response response = probe().get("/bootui/api/metrics");
        assertThat(response.status()).as("GET /bootui/api/metrics status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/metrics content-type (%s)", response.contentType())
                .isTrue();

        assertThat(response.json().path("metricsAvailable").asBoolean(true))
                .as("with no MeterRegistry bean the report is unavailable")
                .isFalse();
        assertThat(response.json().path("total").asInt(-1))
                .as("an unavailable report carries no meters")
                .isZero();
        assertThat(response.json().path("meters").isArray()
                        && response.json().path("meters").isEmpty())
                .as("an unavailable report carries an empty meters array")
                .isTrue();
    }

    @Test
    void metricsPanelIsReportedAvailableInTheManifest() {
        Response panels = probe().get("/bootui/api/panels");
        assertThat(panels.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        boolean metricsAvailable = false;
        for (var panel : panels.json().path("panels")) {
            if ("metrics".equals(panel.path("id").asText(null))) {
                metricsAvailable = panel.path("available").asBoolean(false);
            }
        }
        assertThat(metricsAvailable)
                .as("the Metrics panel is always available on Quarkus, even without a Micrometer registry")
                .isTrue();
    }
}
