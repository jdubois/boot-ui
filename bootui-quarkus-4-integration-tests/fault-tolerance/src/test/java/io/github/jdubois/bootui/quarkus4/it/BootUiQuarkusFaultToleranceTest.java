package io.github.jdubois.bootui.quarkus4.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.faulttolerance.InventoryClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Proves the Quarkus Fault Tolerance panel light-up end to end on an app that <strong>does</strong> have the
 * {@code quarkus-smallrye-fault-tolerance} extension on its classpath: the MicroProfile Fault Tolerance
 * annotations of {@link InventoryClient} are discovered at build time by the deployment processor's Jandex
 * scan, recorded into the synthetic {@code QuarkusFaultTolerancePolicies} bean, mapped by
 * {@code QuarkusFaultTolerancePolicyProvider} onto the neutral {@code FaultTolerancePolicyDto} contract — including
 * MicroProfile configuration overrides and live named-breaker state — and surfaced on
 * {@code GET /bootui/api/fault-tolerance}, with the panel reported available in the manifest.
 *
 * <p>This is the fault-tolerance-<em>present</em> half of the coverage; the sibling
 * {@code bootui-quarkus-integration-tests} module proves the <em>absent</em> path (the panel is reported
 * unavailable and {@code GET /bootui/api/fault-tolerance} renders {@code faultTolerancePresent=false}).</p>
 */
@QuarkusTest
class BootUiQuarkusFaultToleranceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void faultTolerancePanelListsTheAnnotatedPolicies() {
        Response response = probe().get("/bootui/api/fault-tolerance");
        assertThat(response.status())
                .as("GET /bootui/api/fault-tolerance status")
                .isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/fault-tolerance content-type (%s)", response.contentType())
                .isTrue();

        JsonNode root = response.json();
        assertThat(root.path("faultTolerancePresent").asBoolean(false))
                .as("with quarkus-smallrye-fault-tolerance present the report is available")
                .isTrue();
        assertThat(providers(root))
                .as("the SmallRye provider is the only one on Quarkus")
                .containsExactly("smallrye-fault-tolerance");
        assertThat(root.path("totalPolicies").asInt(0))
                .as("all eight declarations are captured")
                .isEqualTo(8);

        JsonNode namedBreaker = policy(root, "inventory-service", "CIRCUIT_BREAKER");
        assertThat(namedBreaker.path("target").asText())
                .as("the protected operation is reported even when the breaker is named")
                .isEqualTo("com.example.faulttolerance.InventoryClient#charge");
        assertThat(namedBreaker.path("state").asText())
                .as("SmallRye exposes live state for named breakers")
                .isEqualTo("CLOSED");
        assertThat(setting(namedBreaker, "requestVolumeThreshold"))
                .as("annotation members render as typed values, not Jandex's member=value form")
                .isEqualTo("4");
        assertThat(setting(namedBreaker, "failureRatio")).isEqualTo("0.5");
        assertThat(setting(namedBreaker, "delayUnit")).isEqualTo("SECONDS");

        JsonNode anonymousBreaker = policy(root, "InventoryClient#settle", "CIRCUIT_BREAKER");
        assertThat(anonymousBreaker.path("state").asText())
                .as("SmallRye cannot report state for an anonymous breaker, and BootUI does not guess")
                .isEqualTo("UNKNOWN");

        // A method annotated with both @Retry and @Fallback yields two policies that share a name and
        // differ by type - the documented uniqueness caveat on FaultTolerancePolicyDto#name - and both must be
        // listed rather than one silently shadowing the other.
        JsonNode retry = policy(root, "InventoryClient#reserve", "RETRY");
        assertThat(setting(retry, "maxRetries")).isEqualTo("3");
        assertThat(setting(retry, "delay")).isEqualTo("50");

        JsonNode fallback = policy(root, "InventoryClient#reserve", "FALLBACK");
        assertThat(setting(fallback, "fallbackMethod")).isEqualTo("reserveUnavailable");

        assertThat(root.path("captureEnabled").asBoolean(false))
                .as("event capture is on, even though SmallRye publishes no per-call event stream")
                .isTrue();
    }

    @Test
    void microProfileConfigurationOverridesAreReportedHonestly() {
        JsonNode root = probe().get("/bootui/api/fault-tolerance").json();

        JsonNode timeout = policy(root, "InventoryClient#exportReport", "TIME_LIMITER");
        assertThat(setting(timeout, "value"))
                .as("the MicroProfile config override wins over the annotation's 2000")
                .isEqualTo("5000");
        assertThat(provenance(timeout, "value"))
                .as("an overridden value is reported as configured, not as the declared default")
                .isEqualTo("CONFIGURED");

        JsonNode bulkhead = policy(root, "InventoryClient#rebuildIndex", "BULKHEAD");
        assertThat(setting(bulkhead, "enabled"))
                .as("a policy disabled through MicroProfile config says so instead of looking active")
                .isEqualTo("false");
        assertThat(provenance(bulkhead, "enabled")).isEqualTo("CONFIGURED");
    }

    @Test
    void faultTolerancePanelIsReportedAvailable() {
        Response response = probe().get("/bootui/api/panels");
        assertThat(response.status()).as("GET /bootui/api/panels status").isEqualTo(200);

        JsonNode panel = panelById(response.json(), "fault-tolerance");
        assertThat(panel.path("available").asBoolean(false))
                .as("the Fault Tolerance panel is available when SmallRye Fault Tolerance is present")
                .isTrue();
    }

    private static java.util.List<String> providers(JsonNode report) {
        java.util.List<String> providers = new java.util.ArrayList<>();
        for (JsonNode provider : report.path("providers")) {
            providers.add(provider.asText());
        }
        return providers;
    }

    private static JsonNode policy(JsonNode report, String name, String type) {
        for (JsonNode policy : report.path("policies")) {
            if (name.equals(policy.path("name").asText(null))
                    && type.equals(policy.path("type").asText(null))) {
                return policy;
            }
        }
        throw new AssertionError("No " + type + " policy named " + name + " in " + report);
    }

    private static String setting(JsonNode policy, String name) {
        return settingNode(policy, name).path("value").asText(null);
    }

    private static String provenance(JsonNode policy, String name) {
        return settingNode(policy, name).path("provenance").asText(null);
    }

    private static JsonNode settingNode(JsonNode policy, String name) {
        for (JsonNode setting : policy.path("settings")) {
            if (name.equals(setting.path("name").asText(null))) {
                return setting;
            }
        }
        throw new AssertionError("No setting named " + name + " on policy " + policy);
    }

    private static JsonNode panelById(JsonNode manifest, String id) {
        for (JsonNode panel : manifest.path("panels")) {
            if (id.equals(panel.path("id").asText(null))) {
                return panel;
            }
        }
        throw new AssertionError("No panel with id " + id + " in manifest " + manifest);
    }
}
