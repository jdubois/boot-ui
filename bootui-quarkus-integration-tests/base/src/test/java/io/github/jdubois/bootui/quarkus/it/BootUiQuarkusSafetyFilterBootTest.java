package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot (over-loopback) checks that the {@link io.github.jdubois.bootui.quarkus.BootUiQuarkusSafetyFilter}
 * is wired into the live Vert.x request chain and behaves at the boundaries the engine policy promises:
 * it rejects a cross-site write with the canonical JSON 403, but does <em>not</em> over-block a
 * safe cross-site read or a same-origin write. The peer-trust and Host-rebinding paths (which cannot be
 * produced over loopback HTTP) are covered by the white-box {@code BootUiQuarkusSafetyFilterTest}; the
 * cross-adapter JSON body contract is also asserted in the shared conformance suite.
 */
@QuarkusTest
class BootUiQuarkusSafetyFilterBootTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void crossSiteWriteIsRejectedWithCanonicalJsonBody() {
        Response response = probe().post(
                        "/bootui/api/loggers",
                        Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));
        assertThat(response.status()).as("cross-site POST must be 403").isEqualTo(403);
        assertThat(response.isJson())
                .as("403 content-type must be JSON (%s)", response.contentType())
                .isTrue();
        assertThat(response.json().path("error").asText())
                .isEqualTo("BootUI rejected a cross-site request to a state-changing endpoint.");
    }

    @Test
    void safeCrossSiteReadIsAllowed() {
        // GET is a safe method: a cross-site Origin/Sec-Fetch-Site must NOT block a read.
        Response response = probe().get(
                        "/bootui/api/overview",
                        Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));
        assertThat(response.status())
                .as("safe cross-site GET must pass the guard")
                .isEqualTo(200);
    }

    @Test
    void sameOriginWriteIsNotBlockedByTheGuard() {
        // Host-only Origin match (the loopback Host is localhost:<port>): the guard must let it through.
        // Routing then answers 405 because /bootui/api/overview is GET-only — proving the guard did not 403.
        Response response = probe().post("/bootui/api/overview", Map.of("Origin", "http://localhost"));
        assertThat(response.status())
                .as("same-origin write must not be rejected (403) by the safety guard")
                .isNotEqualTo(403);
    }
}
