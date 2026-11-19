package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

/** Exercises the actual connection boundary, not a loopback request with an untrusted header. */
public final class ForwardedPeerContract {

    private ForwardedPeerContract() {}

    public static void verify(String baseUrl) {
        var source = BootUiRawHttpProbe.nonLoopbackSource();
        assumeTrue(source.isPresent(), "A real non-loopback local interface is required");
        var probe = new BootUiRawHttpProbe(baseUrl);
        for (Map<String, String> headers : java.util.List.of(
                Map.of("Host", "localhost"),
                Map.of("Host", "localhost", "X-Forwarded-For", "127.0.0.1"),
                Map.of("Host", "localhost", "Forwarded", "for=127.0.0.1;proto=http;host=localhost"))) {
            var response = probe.request("GET", "/bootui/api/overview", headers, null, source.orElseThrow());
            assertThat(response.status())
                    .as("real non-loopback socket, forwarded headers %s", headers.keySet())
                    .isEqualTo(403);
            assertThat(response.json().path("error").asText()).startsWith("BootUI is restricted to loopback requests.");
        }
        var local = probe.request("GET", "/bootui/api/overview", Map.of("Host", "localhost"), null, null);
        assertThat(local.status()).as("ordinary loopback remains usable").isEqualTo(200);
        for (Map<String, String> headers : java.util.List.of(
                Map.of("Host", "foreign.invalid", "X-Forwarded-Host", "localhost"),
                Map.of("Host", "foreign.invalid", "Forwarded", "for=127.0.0.1;host=localhost;proto=http"))) {
            var response = probe.request("GET", "/bootui/api/overview", headers, null, null);
            assertThat(response.status())
                    .as("forwarding does not replace the raw Host allow-list")
                    .isEqualTo(403);
            assertThat(response.json().path("error").asText())
                    .startsWith("BootUI rejected an unrecognized Host header.");
        }
    }
}
