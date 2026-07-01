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
 * Verifies that the HTTP Probe resolves the application's port even when the server binds to a
 * <em>random</em> port ({@code quarkus.http.test-port=0}).
 *
 * <p>This pins a non-obvious Quarkus behavior that {@code QuarkusServerPortSupplier} relies on: when the
 * server binds a random port, Quarkus rewrites the {@code quarkus.http.test-port} system property to the
 * <em>actual</em> bound port after startup. Because the supplier reads MicroProfile Config <em>live</em> on
 * every probe (system properties being a config source), it picks up that resolved port — so the
 * documented "random port cannot be recovered" caveat does not actually bite for a probe issued after the
 * server is up. If this ever regresses (e.g. a future Quarkus stops rewriting the property), the self-probe
 * below would target {@code localhost:0} and fail, turning this into a failing guard rather than a silent
 * change in behavior.</p>
 */
@QuarkusTest
@TestProfile(BootUiQuarkusHttpProbeRandomPortTest.RandomTestPort.class)
class BootUiQuarkusHttpProbeRandomPortTest {

    public static class RandomTestPort implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.test-port", "0");
        }
    }

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    @TestHTTPResource
    URL baseUrl;

    @Test
    void resolvesTheActualBoundPortUnderARandomTestPort() {
        Response response = new BootUiHttpProbe(baseUrl.toExternalForm())
                .request(
                        "POST",
                        "/bootui/api/http-probe",
                        JSON_HEADERS,
                        "{\"method\":\"GET\",\"path\":\"/bootui/api/overview\"}");

        assertThat(response.status()).as("POST /bootui/api/http-probe status").isEqualTo(200);
        assertThat(response.json().path("status").asInt())
                .as("with a random bound port, the live config read still resolves the actual port (probe 200)")
                .isEqualTo(200);
    }
}
