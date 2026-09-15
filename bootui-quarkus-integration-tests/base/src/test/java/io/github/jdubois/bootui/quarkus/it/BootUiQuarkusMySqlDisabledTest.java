package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiQuarkusMySqlDisabledTest.DisabledProfile.class)
class BootUiQuarkusMySqlDisabledTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void disabledPanelBlocksBothMethodsAndSafetyStillRunsFirst() {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        for (var denied :
                java.util.List.of(probe.get("/bootui/api/mysql"), probe.post("/bootui/api/mysql/read", Map.of()))) {
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.json().path("error").asText()).isEqualTo("BootUI panel access denied");
            assertThat(denied.json().path("panel").asText()).isEqualTo("mysql");
            assertThat(denied.json().path("reason").asText())
                    .isEqualTo("Panel is disabled via bootui.panels.mysql.enabled=false");
        }
        var crossSite = probe.post(
                "/bootui/api/mysql/read", Map.of("Origin", "http://evil.example.com", "Sec-Fetch-Site", "cross-site"));
        assertThat(crossSite.status()).isEqualTo(403);
        assertThat(crossSite.json().path("error").asText())
                .isEqualTo("BootUI rejected a cross-site request to a state-changing endpoint.");
        assertThat(crossSite.json().has("panel")).isFalse();
    }

    public static final class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.panels.mysql.enabled", "false");
        }
    }
}
