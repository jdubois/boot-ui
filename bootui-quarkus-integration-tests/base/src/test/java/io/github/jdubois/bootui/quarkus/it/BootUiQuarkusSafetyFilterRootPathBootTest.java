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
 * Real-boot proof that the {@link io.github.jdubois.bootui.quarkus.BootUiQuarkusSafetyFilter} keeps
 * guarding the console when the host application runs under a non-default {@code quarkus.http.root-path}.
 *
 * <p>Quarkus relocates the whole application — including BootUI's JAX-RS resources — under the root-path,
 * so here the console is served at {@code /app/bootui/**} while the global Vert.x filter still sees the
 * full path. Before the root-path strip the filter matched the literal {@code /bootui} and silently let
 * {@code /app/bootui/**} through (fail-open). This boot test is the ground truth for that regression: a
 * cross-site write to the prefixed endpoint must be rejected with the canonical 403, and a same-origin
 * read at the prefixed path must still be served (so the assertion is meaningful and not just a 404).</p>
 */
@QuarkusTest
@TestProfile(BootUiQuarkusSafetyFilterRootPathBootTest.RootPathProfile.class)
class BootUiQuarkusSafetyFilterRootPathBootTest {

    @TestHTTPResource
    URL baseUrl;

    /** The HTTP server root (scheme://host:port), independent of how {@code baseUrl} renders the root-path. */
    private BootUiHttpProbe probe() {
        String serverRoot = baseUrl.getProtocol() + "://" + baseUrl.getHost() + ":" + baseUrl.getPort();
        return new BootUiHttpProbe(serverRoot);
    }

    @Test
    void consoleIsServedUnderTheRootPath() {
        Response response = probe().get("/app/bootui/api/overview");
        assertThat(response.status())
                .as("the console must be reachable at the root-path-prefixed path")
                .isEqualTo(200);
    }

    @Test
    void crossSiteWriteUnderTheRootPathIsRejected() {
        Response response = probe().post(
                        "/app/bootui/api/overview",
                        Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));
        assertThat(response.status())
                .as("a cross-site write to the prefixed console must be guarded (was fail-open at /app/bootui)")
                .isEqualTo(403);
        assertThat(response.json().path("error").asText())
                .isEqualTo("BootUI rejected a cross-site request to a state-changing endpoint.");
    }

    public static final class RootPathProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.root-path", "/app");
        }
    }
}
