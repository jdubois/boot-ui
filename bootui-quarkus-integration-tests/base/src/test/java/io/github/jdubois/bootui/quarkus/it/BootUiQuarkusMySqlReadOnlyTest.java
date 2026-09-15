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
@TestProfile(BootUiQuarkusMySqlReadOnlyTest.ReadOnlyProfile.class)
class BootUiQuarkusMySqlReadOnlyTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void readOnlyPreservesCachedGetButRejectsExternalReadWithTheCanonicalBody() {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        assertThat(probe.get("/bootui/api/mysql").status()).isEqualTo(200);
        var denied = probe.post("/bootui/api/mysql/read", Map.of("Content-Type", "application/json"));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.json().path("error").asText()).isEqualTo("BootUI panel access denied");
        assertThat(denied.json().path("panel").asText()).isEqualTo("mysql");
        assertThat(denied.json().path("reason").asText())
                .isEqualTo("Panel is read-only via bootui.panels.mysql.read-only=true");
        assertThat(probe.get("/bootui/api/mysql").json().path("status").asText())
                .isEqualTo("NOT_READ");
    }

    public static final class ReadOnlyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.panels.mysql.read-only", "true");
        }
    }
}
