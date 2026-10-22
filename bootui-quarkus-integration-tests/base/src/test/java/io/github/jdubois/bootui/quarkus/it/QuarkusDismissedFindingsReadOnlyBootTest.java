package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot proof that the dismissed-findings writes obey the global read-only policy on Quarkus, including paths that
 * carry matrix parameters or an encoded slash: the JAX-RS router drops the former and decodes the latter before it
 * dispatches, so a guard that read the raw path would let such a request through.
 */
@QuarkusTest
@TestProfile(QuarkusDismissedFindingsReadOnlyBootTest.ReadOnlyProfile.class)
class QuarkusDismissedFindingsReadOnlyBootTest {

    private static final String BODY = "{\"ruleId\":\"HIB-ID-006\",\"identity\":\"v1:unit=a|b=c\",\"note\":\"n\"}";

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void everySpellingOfTheWritePathIsRefusedWhileTheListStaysReadable() {
        BootUiHttpProbe probe = probe();
        Map<String, String> headers = Map.of("Content-Type", "application/json", "Origin", baseUrl.toExternalForm());
        for (String path : List.of(
                "/bootui/api/dismissed-findings",
                "/bootui/api/dismissed-findings/",
                "/bootui/api/dismissed-findings;x=1",
                "/bootui/api;x=1/dismissed-findings",
                "/bootui/api%2Fdismissed-findings;x=1",
                "/bootui%2Fapi/dismissed-findings;x=1")) {
            for (String method : List.of("POST", "DELETE")) {
                Response response = probe.request(method, path, headers, BODY);
                assertThat(response.status()).as(method + " " + path).isIn(403, 404);
                assertThat(response.body()).as(method + " " + path).doesNotContain("\"dismissed\"");
            }
        }
        assertThat(probe.request("POST", "/bootui/api/dismissed-findings", headers, BODY)
                        .json()
                        .path("panel")
                        .asText())
                .isEqualTo("dismissed-findings");
        Response list = probe.get("/bootui/api/dismissed-findings");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.json().path("dismissed")).isEmpty();
    }

    public static final class ReadOnlyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.read-only", "true");
        }
    }
}
