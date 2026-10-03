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
 * Regression test for a build-step cycle on apps that combine OpenTelemetry logging with Dev Services.
 *
 * <p>The OpenTelemetry log handler needs the bean container before logging is set up, and Dev Services
 * (Compose and datasources) need logging set up before they start. BootUI once turned the Dev Services results
 * into a synthetic bean, which put Dev Services upstream of the bean container and closed the loop, so Quarkus
 * refused to boot. The profile enables OpenTelemetry logs and declares a named H2 datasource without a URL, so
 * Quarkus starts an in-process H2 Dev Service (no Docker). Booting at all proves the cycle is gone; the
 * assertions prove the Dev Services panel still lists the started service with its credentials masked.</p>
 */
@QuarkusTest
@TestProfile(BootUiQuarkusDevServicesWithOtelLogsTest.OtelLogsWithDevServicesProfile.class)
class BootUiQuarkusDevServicesWithOtelLogsTest {

    public static final class OtelLogsWithDevServicesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.otel.logs.enabled", "true",
                    "quarkus.otel.logs.exporter", "none",
                    "quarkus.datasource.devsvc.db-kind", "h2",
                    "quarkus.datasource.devsvc.jdbc.min-size", "0");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void devServicesPanelIsAvailable() {
        JsonNode panel = null;
        for (JsonNode candidate : probe().get("/bootui/api/panels").json().path("panels")) {
            if ("dev-services".equals(candidate.path("id").asText(null))) {
                panel = candidate;
            }
        }
        assertThat(panel).as("the Dev Services panel is in the manifest").isNotNull();
        assertThat(panel.path("available").asBoolean(false))
                .as("a started Dev Service lights the panel up")
                .isTrue();
    }

    @Test
    void devServicesReportListsTheH2DevServiceWithMaskedCredentials() {
        Response response = probe().get("/bootui/api/dev-services");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.path("total").asInt(0))
                .as("the H2 Dev Service is captured")
                .isPositive();

        JsonNode h2 = null;
        for (JsonNode service : body.path("services")) {
            if (service.path("connectionDetails").has("quarkus.datasource.devsvc.username")) {
                h2 = service;
            }
        }
        assertThat(h2)
                .as("one service injected the devsvc datasource credentials: %s", body)
                .isNotNull();
        assertThat(h2.path("source").asText(null)).isEqualTo("Quarkus Dev Services");
        assertThat(h2.path("connectionDetails")
                        .path("quarkus.datasource.devsvc.password")
                        .asText())
                .as("Dev Service passwords are masked")
                .isEqualTo("******");
    }
}
