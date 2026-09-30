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
 * Real-boot regression proof that BootUI's own traffic stays out of the telemetry it reports on when the
 * host application runs under a non-default {@code quarkus.http.root-path}. The capture filters used to
 * match the literal {@code /bootui} prefix, so a console request arriving as {@code /app/bootui/**} was
 * retained as host-application traffic; they now strip the root path through the same shared helper the
 * safety and panel-access filters use (see {@code BootUiQuarkusSafetyFilterRootPathBootTest} and
 * {@code QuarkusPanelAccessFilterRootPathBootTest} for their root-path proofs).
 *
 * <p>Application traffic under the same root path must keep being captured, so this also pins that the
 * exclusion did not widen into the host application.</p>
 */
@QuarkusTest
@TestProfile(BootUiQuarkusHttpExchangesRootPathCaptureTest.RootPathProfile.class)
class BootUiQuarkusHttpExchangesRootPathCaptureTest {

    @TestHTTPResource
    URL baseUrl;

    /** The HTTP server root (scheme://host:port), independent of how {@code baseUrl} renders the root-path. */
    private BootUiHttpProbe probe() {
        String serverRoot = baseUrl.getProtocol() + "://" + baseUrl.getHost() + ":" + baseUrl.getPort();
        return new BootUiHttpProbe(serverRoot);
    }

    @Test
    void consoleTrafficUnderTheRootPathIsExcludedWhileApplicationTrafficIsCaptured() {
        BootUiHttpProbe probe = probe();
        probe.get("/app/api/hello");
        probe.get("/app/bootui/api/overview");

        Response report = probe.get("/app/bootui/api/http-exchanges");
        assertThat(report.status())
                .as("GET /app/bootui/api/http-exchanges status")
                .isEqualTo(200);

        boolean foundHello = false;
        for (JsonNode exchange : report.json().path("exchanges")) {
            String path = exchange.path("path").asText("");
            assertThat(path)
                    .as("BootUI's own root-path-prefixed traffic must never be retained as host telemetry")
                    .doesNotContain("/bootui");
            if (path.equals("/app/api/hello")) {
                foundHello = true;
            }
        }
        assertThat(foundHello)
                .as("host-application traffic under the same root path must still be captured")
                .isTrue();
    }

    @Test
    void consoleTrafficUnderTheRootPathIsExcludedFromLiveActivity() {
        BootUiHttpProbe probe = probe();
        probe.get("/app/api/hello");
        probe.get("/app/bootui/api/overview");

        Response activity = probe.get("/app/bootui/api/activity");
        assertThat(activity.status()).as("GET /app/bootui/api/activity status").isEqualTo(200);

        boolean foundHello = false;
        for (JsonNode entry : activity.json().path("entries")) {
            String path = entry.path("path").asText("");
            assertThat(path)
                    .as("Live Activity correlates the same capture source, so it must be self-excluded too")
                    .doesNotContain("/bootui");
            if (path.equals("/app/api/hello")) {
                foundHello = true;
            }
        }
        assertThat(foundHello)
                .as("host-application traffic under the same root path must still reach Live Activity")
                .isTrue();
    }

    /**
     * The declared JAX-RS patterns are relative to the application, so they are matched under the same root
     * path the captured request carries: {@code /app/widgets} resolves to its declared route, not a masked path.
     */
    @Test
    void routeRankingsResolveDeclaredRoutesUnderTheRootPath() {
        BootUiHttpProbe probe = probe();
        probe.get("/app/widgets");

        Response report = probe.get("/app/bootui/api/http-exchanges?q=widgets");
        JsonNode exchange = report.json().path("exchanges").path(0);
        assertThat(exchange.path("path").asText()).isEqualTo("/app/widgets");
        assertThat(exchange.path("route").asText()).isEqualTo("/app/widgets");
        assertThat(exchange.path("routeSource").asText()).isEqualTo("DECLARED_MAPPING");
    }

    public static final class RootPathProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.root-path", "/app");
        }
    }
}
