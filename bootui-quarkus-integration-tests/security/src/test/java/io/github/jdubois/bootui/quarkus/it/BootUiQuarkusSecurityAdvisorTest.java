package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BootUiQuarkusSecurityAdvisorTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void flagsPlainTextEmbeddedPasswordsFromTheRunningQuarkusApplication() {
        BootUiHttpProbe.Response response = new BootUiHttpProbe(baseUrl.toExternalForm())
                .post("/bootui/api/security/scan", Map.of("Content-Type", "application/json"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("results"))
                .anySatisfy(result -> assertThat(result.path("id").asText()).isEqualTo("QS-AUTH-013"));
    }
}
