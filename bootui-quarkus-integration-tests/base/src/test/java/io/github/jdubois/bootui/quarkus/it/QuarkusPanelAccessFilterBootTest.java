package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Real-boot (over-loopback) checks that {@code io.github.jdubois.bootui.quarkus.QuarkusPanelAccessFilter}
 * is wired into the live Vert.x request chain and enforces {@code bootui.panels.memory.enabled} at
 * behavioral parity with the Spring adapter's {@code PanelAccessFilter}. The exhaustive per-panel/
 * per-config-key matrix is covered by the white-box {@code QuarkusPanelAccessFilterTest}; this class only
 * proves what a plain unit test of {@code handle(RoutingContext)} cannot: that the filter is actually
 * registered on the live filter chain, and — critically — that it runs <em>after</em>
 * {@code BootUiQuarkusSafetyFilter}, so a request that fails both the safety guard and panel gating is
 * rejected with the safety filter's reason, never the panel filter's.
 */
@QuarkusTest
@TestProfile(QuarkusPanelAccessFilterBootTest.DisabledMemoryPanelProfile.class)
class QuarkusPanelAccessFilterBootTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void disabledPanelReadIsRejectedWithCanonicalJsonBody() {
        Response response = probe().get("/bootui/api/memory");

        assertThat(response.status()).as("disabled panel GET must be 403").isEqualTo(403);
        assertThat(response.isJson())
                .as("403 content-type must be JSON (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("error").asText()).isEqualTo("BootUI panel access denied");
        assertThat(response.json().path("panel").asText()).isEqualTo("memory");
        assertThat(response.json().path("reason").asText())
                .isEqualTo("Panel is disabled via bootui.panels.memory.enabled=false");
    }

    @Test
    void disabledPanelActionIsAlsoRejected() {
        Response response = probe().post("/bootui/api/memory/scan", Map.of());

        assertThat(response.status()).as("disabled panel POST must be 403").isEqualTo(403);
        assertThat(response.json().path("panel").asText()).isEqualTo("memory");
    }

    @Test
    void overviewBypassesPanelGatingEvenWhenAnotherPanelIsDisabled() {
        // The Overview shell-chrome endpoint is never gated by any panel's toggle (BootUiPanels.OVERVIEW
        // registers no API prefix), regardless of other panels being disabled.
        Response response = probe().get("/bootui/api/overview");

        assertThat(response.status()).as("Overview must never be panel-gated").isEqualTo(200);
    }

    @Test
    void unaffectedPanelIsNotOverBlocked() {
        // Sanity check: disabling one panel (memory) must not affect an unrelated panel.
        Response response = probe().get("/bootui/api/cache");

        assertThat(response.status())
                .as("an unrelated panel must be unaffected")
                .isEqualTo(200);
    }

    @Test
    void safetyFilterRejectionWinsOverDisabledPanelGating() {
        // memory is disabled AND this is a cross-site write: the safety filter (higher priority, runs
        // first) must reject it before the panel-access filter (lower priority) ever runs, so the body
        // carries the safety filter's cross-site message, not the panel-disabled reason.
        Response response = probe().post(
                        "/bootui/api/memory/scan",
                        Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));

        assertThat(response.status()).as("cross-site POST must be 403").isEqualTo(403);
        assertThat(response.json().path("error").asText())
                .as("the safety filter's rejection must win, not the panel-access filter's")
                .isEqualTo("BootUI rejected a cross-site request to a state-changing endpoint.");
        assertThat(response.json().has("panel"))
                .as("the safety filter's JSON body has no \"panel\" field")
                .isFalse();
    }

    public static final class DisabledMemoryPanelProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.panels.memory.enabled", "false");
        }
    }
}
