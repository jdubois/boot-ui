package io.github.jdubois.bootui.quarkus4.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.github.jdubois.bootui.spi.HealthProbeManifest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Pins the Quarkus-specific content of the JVM Tuning panel ({@code GET /bootui/api/jvm-tuning}), which
 * shares the engine {@code MemoryReportProvider} with the Live Memory panel but additionally renders the
 * virtual-threads advisory and the Kubernetes recommendation. These are the two facts the shared
 * conformance suite cannot assert because they are framework-specific:
 *
 * <ul>
 *   <li>Quarkus has no application-wide virtual-threads switch, so {@code calculation.virtualThreadsProperty}
 *       is {@code null} (the Vue view uses this to omit the app-wide VT advisory entirely).</li>
 *   <li>The Kubernetes snippet uses SmallRye Health paths, never Spring Actuator ones. SmallRye Health is
 *       absent from this integration-test classpath, so by default the probe stanzas are omitted and the
 *       omitted-probes warning is the framework-neutral one. Forcing the toggle on
 *       ({@code ?kubernetesActuatorEnabled=true}) renders the {@code /q/health/*} probe paths and never the
 *       Spring {@code MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED} env entry.</li>
 * </ul>
 */
@QuarkusTest
class BootUiQuarkusJvmTuningResourceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void jvmTuningReportOmitsTheAppWideVirtualThreadsSwitch() {
        Response response = probe().get("/bootui/api/jvm-tuning");
        assertThat(response.status()).as("GET /bootui/api/jvm-tuning status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/jvm-tuning content-type (%s)", response.contentType())
                .isTrue();

        JsonNode report = response.json();
        JsonNode calculation = report.path("calculation");
        JsonNode virtualThreadsProperty = calculation.path("virtualThreadsProperty");
        assertThat(virtualThreadsProperty.isTextual())
                .as("Quarkus has no application-wide virtual-threads property, so it must not be a string")
                .isFalse();
        assertThat(calculation.path("valid").isBoolean()).isTrue();
        assertThat(report.path("kubernetes").path("healthProbesEnabled").asBoolean(true))
                .isFalse();
        if (calculation.path("valid").asBoolean()) {
            assertThat(calculation.path("error").isNull()).isTrue();
            assertThat(report.path("suggestedJvmOptions").asText()).contains("-Xms", "-Xmx");
            assertThat(report.path("kubernetes").path("yaml").asText()).contains("JAVA_TOOL_OPTIONS");
        } else {
            assertThat(calculation.path("error").asText()).isNotBlank();
            assertThat(calculation.path("heapBytes").asLong()).isZero();
            assertThat(calculation.path("jvmOptions").asText()).isEmpty();
            assertThat(report.path("suggestedJvmOptions").asText()).isEmpty();
            assertThat(report.path("kubernetes").path("javaToolOptions").asText())
                    .isEmpty();
            assertThat(report.path("kubernetes").path("yaml").asText()).isEmpty();
            assertThat(report.path("kubernetes").path("qosClass").asText()).isEqualTo("Unavailable");
            assertThat(report.path("kubernetes").path("warnings")).contains(calculation.path("error"));
        }
    }

    @Test
    void kubernetesSnippetOmitsProbesByDefaultWithTheNeutralWarning() {
        Response response = probe().get("/bootui/api/jvm-tuning?totalMemoryMb=65536");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("calculation").path("valid").asBoolean())
                .isTrue();
        JsonNode kubernetes = response.json().path("kubernetes");

        assertThat(kubernetes.path("healthProbesEnabled").asBoolean(true))
                .as("SmallRye Health is absent from the IT classpath, so probes default off")
                .isFalse();
        assertThat(kubernetes.path("yaml").asText())
                .as("with probes off the snippet has no health-probe paths")
                .doesNotContain("/q/health")
                .doesNotContain("/actuator/health");

        boolean hasNeutralOmittedWarning = false;
        for (JsonNode warning : kubernetes.path("warnings")) {
            if (warning.asText().equals(HealthProbeManifest.QUARKUS_SMALLRYE.probesOmittedWarning())) {
                hasNeutralOmittedWarning = true;
            }
        }
        assertThat(hasNeutralOmittedWarning)
                .as(
                        "the omitted-probes warning must be the framework-neutral SmallRye one, not the Spring Actuator text")
                .isTrue();
    }

    @Test
    void kubernetesSnippetUsesSmallRyePathsWhenProbesAreForcedOn() {
        Response response = probe().get("/bootui/api/jvm-tuning?totalMemoryMb=65536&kubernetesActuatorEnabled=true");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("calculation").path("valid").asBoolean())
                .isTrue();
        JsonNode kubernetes = response.json().path("kubernetes");

        assertThat(kubernetes.path("healthProbesEnabled").asBoolean(false)).isTrue();
        String yaml = kubernetes.path("yaml").asText();
        assertThat(yaml)
                .as("forced-on probes must render the SmallRye startup/liveness/readiness paths")
                .contains("/q/health/started")
                .contains("/q/health/live")
                .contains("/q/health/ready")
                .contains("port: http")
                .doesNotContain("port: 8080");
        assertThat(yaml)
                .as("Quarkus has no Spring Actuator enabling env var, so it must never appear")
                .doesNotContain("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED")
                .doesNotContain("/actuator/health");
    }
}
