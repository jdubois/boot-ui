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
 * Real-boot proof that {@code io.github.jdubois.bootui.quarkus.QuarkusPanelAccessFilter} keeps enforcing
 * panel gating when the host application runs under a non-default {@code quarkus.http.root-path} —
 * mirroring {@code BootUiQuarkusSafetyFilterRootPathBootTest} for the panel-access filter (and the Spring
 * adapter's {@code honorsCustomApiPathAndContextPath} test). Both global Vert.x filters share the same
 * {@code QuarkusRootPath} prefix-stripping helper; this pins that the panel-access filter's use of it is
 * wired correctly end-to-end, not just unit-tested in isolation.
 */
@QuarkusTest
@TestProfile(QuarkusPanelAccessFilterRootPathBootTest.RootPathDisabledMemoryProfile.class)
class QuarkusPanelAccessFilterRootPathBootTest {

    @TestHTTPResource
    URL baseUrl;

    /** The HTTP server root (scheme://host:port), independent of how {@code baseUrl} renders the root-path. */
    private BootUiHttpProbe probe() {
        String serverRoot = baseUrl.getProtocol() + "://" + baseUrl.getHost() + ":" + baseUrl.getPort();
        return new BootUiHttpProbe(serverRoot);
    }

    @Test
    void disabledPanelUnderTheRootPathIsRejected() {
        Response response = probe().get("/app/bootui/api/memory");

        assertThat(response.status())
                .as("a disabled panel under the root-path-prefixed console must still be gated")
                .isEqualTo(403);
        assertThat(response.json().path("panel").asText()).isEqualTo("memory");
        assertThat(response.json().path("reason").asText())
                .isEqualTo("Panel is disabled via bootui.panels.memory.enabled=false");
    }

    @Test
    void overviewUnderTheRootPathIsStillNeverGated() {
        Response response = probe().get("/app/bootui/api/overview");

        assertThat(response.status())
                .as("Overview must stay ungated under a non-default root-path too")
                .isEqualTo(200);
    }

    public static final class RootPathDisabledMemoryProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.root-path", "/app",
                    "bootui.panels.memory.enabled", "false");
        }
    }
}
