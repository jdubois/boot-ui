package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
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
 *
 * <p>Also verifies that the security response-header policy ({@link BootUiSecurityHeaders}) is applied
 * to all BootUI responses, including 403 rejections.</p>
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

    // --- security headers are present on allowed API responses -----------------------------------

    @Test
    void securityHeadersArePresentOnApiResponse() {
        Response response = probe().get("/bootui/api/overview");

        assertThat(response.status()).as("API response must succeed").isEqualTo(200);
        assertCommonSecurityHeaders(response);
        assertThat(response.header(BootUiSecurityHeaders.CACHE_CONTROL))
                .as("API response must not be cached")
                .isEqualTo(BootUiSecurityHeaders.NO_STORE);
    }

    @Test
    void securityHeadersArePresentOn403RejectionResponse() {
        Response response = probe().post(
                        "/bootui/api/loggers",
                        Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));

        assertThat(response.status()).as("cross-site POST must be 403").isEqualTo(403);
        // Security headers must be set even on rejection responses.
        assertCommonSecurityHeaders(response);
    }

    @Test
    void cspDoesNotContainUnsafeEval() {
        Response response = probe().get("/bootui/api/overview");

        assertThat(response.header(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .as("CSP must not contain unsafe-eval")
                .doesNotContain("unsafe-eval");
    }

    @Test
    void cspContainsFrameAncestorsNone() {
        Response response = probe().get("/bootui/api/overview");

        assertThat(response.header(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .as("CSP must deny frame embedding")
                .contains("frame-ancestors 'none'");
    }

    // --- helpers -------------------------------------------------------------------------------

    private void assertCommonSecurityHeaders(Response response) {
        assertThat(response.header(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .as("Content-Security-Policy must be set")
                .isNotNull();
        assertThat(response.header(BootUiSecurityHeaders.X_CONTENT_TYPE_OPTIONS))
                .as("X-Content-Type-Options must be nosniff")
                .isEqualTo(BootUiSecurityHeaders.NOSNIFF);
        assertThat(response.header(BootUiSecurityHeaders.X_FRAME_OPTIONS))
                .as("X-Frame-Options must be DENY")
                .isEqualToIgnoringCase(BootUiSecurityHeaders.DENY);
        assertThat(response.header(BootUiSecurityHeaders.REFERRER_POLICY))
                .as("Referrer-Policy must be set")
                .isEqualTo(BootUiSecurityHeaders.STRICT_ORIGIN_WHEN_CROSS_ORIGIN);
        assertThat(response.header(BootUiSecurityHeaders.PERMISSIONS_POLICY))
                .as("Permissions-Policy must be set")
                .isEqualTo(BootUiSecurityHeaders.PERMISSIONS_POLICY_VALUE);
        assertThat(response.header(BootUiSecurityHeaders.CACHE_CONTROL))
                .as("Cache-Control must be set")
                .isNotNull();
    }
}
