package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

/** Raw management protection must not cost the console its native in-process endpoint beans. */
public final class ActuatorSecurityContract {

    public static final String TOKEN = "actuator-security-test-token";

    private ActuatorSecurityContract() {}

    public static void verify(
            String applicationUrl,
            String managementUrl,
            String basePath,
            String envPath,
            boolean ownsExposure,
            boolean ownsDetails) {
        var source = BootUiRawHttpProbe.nonLoopbackSource();
        assumeTrue(source.isPresent(), "A real non-loopback local interface is required");
        var management = new BootUiRawHttpProbe(managementUrl);
        for (String endpoint : new String[] {envPath, "health", "health/readiness"}) {
            boolean guarded = endpoint.equals(envPath) ? ownsExposure : ownsDetails;
            var response = management.request(
                    "GET",
                    basePath + "/" + endpoint,
                    Map.of("Host", "localhost", "Authorization", "Bearer " + TOKEN),
                    null,
                    source.orElseThrow());
            if (guarded) {
                assertThat(response.status())
                        .as("BootUI-contributed management data %s", endpoint)
                        .isEqualTo(403);
                assertThat(response.json().path("error").asText())
                        .startsWith("BootUI is restricted to loopback requests.");
            } else {
                assertThat(response.body())
                        .as("host-owned management policy remains native for %s", endpoint)
                        .doesNotContain("BootUI is restricted to loopback requests.");
            }
        }
        String guardedPath = basePath + "/" + (ownsExposure ? envPath : "health");
        if (ownsExposure || ownsDetails) {
            var response = management.request("GET", guardedPath, Map.of("Host", "foreign.invalid"), null, null);
            assertThat(response.status()).as("raw Actuator Host guard").isEqualTo(403);
            assertThat(response.json().path("error").asText())
                    .isEqualTo(
                            "BootUI rejected an unrecognized Host header. Add it to bootui.allowed-hosts to allow this hostname.");
        }
        var application = new BootUiRawHttpProbe(applicationUrl);
        for (String panel : new String[] {"config", "health", "loggers", "panels"}) {
            var response = application.request(
                    "GET",
                    "/bootui/api/" + panel,
                    Map.of("Host", "localhost", "Authorization", "Bearer " + TOKEN),
                    null,
                    source.orElseThrow());
            assertThat(response.status())
                    .as("remote-token in-process panel %s", panel)
                    .isEqualTo(200);
            if (panel.equals("config")) {
                assertThat(response.json().path("properties").isEmpty())
                        .as("native configuration endpoint remains usable")
                        .isFalse();
            } else if (panel.equals("loggers")) {
                assertThat(response.json().path("loggers").isEmpty())
                        .as("native loggers endpoint remains usable")
                        .isFalse();
            } else if (panel.equals("health")) {
                assertThat(response.json().path("available").asBoolean())
                        .as("native health endpoint remains usable")
                        .isTrue();
            }
        }
        var withoutToken = application.request(
                "GET", "/bootui/api/overview", Map.of("Host", "localhost"), null, source.orElseThrow());
        assertThat(withoutToken.status())
                .as("remote console still requires its API token")
                .isEqualTo(401);
    }

    public static void verifyHealthAlias(String applicationUrl, String alias) {
        var source = BootUiRawHttpProbe.nonLoopbackSource();
        assumeTrue(source.isPresent(), "A real non-loopback local interface is required");
        var response = new BootUiRawHttpProbe(applicationUrl)
                .request("GET", alias, Map.of("Host", "localhost"), null, source.orElseThrow());
        assertThat(response.status())
                .as("BootUI-added health details at additional main-listener path")
                .isEqualTo(403);
        assertThat(response.json().path("error").asText()).startsWith("BootUI is restricted to loopback requests.");
    }

    public static void verifyCaptureConfirmation(String applicationUrl) {
        var probe = new BootUiHttpProbe(applicationUrl);
        probe.get("/bootui/api/overview");
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Origin", applicationUrl);
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        for (String body : new String[] {"", "{}", "{\"confirm\":false}"}) {
            var response = probe.request("POST", "/bootui/api/heap-dump/capture", headers, body);
            assertThat(response.status())
                    .as("capture requires explicit JSON confirmation")
                    .isEqualTo(400);
            assertThat(response.json().path("error").asText())
                    .isEqualTo("Heap dump capture requires explicit confirmation.");
        }
        var confirmed = probe.request("POST", "/bootui/api/heap-dump/capture", headers, "{\"confirm\":true}");
        assertThat(confirmed.status())
                .as("confirmed capture reaches the disabled test collector")
                .isEqualTo(200);
        assertThat(confirmed.json().path("capture").path("message").asText()).contains("capture-enabled=false");
    }
}
