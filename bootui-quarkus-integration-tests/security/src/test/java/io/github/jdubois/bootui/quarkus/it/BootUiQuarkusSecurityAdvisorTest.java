package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.quarkus.security.QuarkusSecuritySnapshotProviderImpl;
import io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BootUiQuarkusSecurityAdvisorTest {

    @TestHTTPResource
    URL baseUrl;

    @Inject
    Config config;

    @Test
    void ordinaryNativeSecurityAnnotationsRemainKnownDespiteFrameworkTransformers() {
        var endpoints = new QuarkusSecuritySnapshotProviderImpl(config)
                .snapshot()
                .evidence()
                .endpoints();
        assertThat(endpoints).anySatisfy(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/advisor-policy/exact");
            assertThat(endpoint.access()).isEqualTo(QuarkusSecurityEndpoint.Access.PERMIT);
        });
        assertThat(endpoints).anySatisfy(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/advisor-policy/denied");
            assertThat(endpoint.access()).isEqualTo(QuarkusSecurityEndpoint.Access.RESTRICTED);
        });
    }

    @Test
    void flagsPlainTextEmbeddedPasswordsFromTheRunningQuarkusApplication() {
        BootUiHttpProbe.Response response = new BootUiHttpProbe(baseUrl.toExternalForm())
                .post("/bootui/api/security/scan", Map.of("Content-Type", "application/json"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("evidence").path("usable").asBoolean()).isTrue();
        assertThat(response.json().path("evidence").path("coverageComplete").isBoolean())
                .isTrue();
        assertThat(new BootUiHttpProbe(baseUrl.toExternalForm())
                        .get("/bootui/api/security")
                        .json()
                        .path("evidence"))
                .isEqualTo(response.json().path("evidence"));
        assertThat(response.json().path("results"))
                .anySatisfy(result -> assertThat(result.path("id").asText()).isEqualTo("QS-AUTH-013"));
    }

    @Test
    void nativePermissionMatchingDistinguishesExactPathAndDeniesUnmatchedMethods() {
        var probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        assertThat(probe.get("/advisor-policy/exact").status()).isIn(401, 403);
        assertThat(probe.get("/advisor-policy/exact/child").status()).isEqualTo(200);
        assertThat(probe.get("/advisor-policy/method").status()).isEqualTo(200);
        assertThat(probe.post("/advisor-policy/method", Map.of()).status()).isIn(401, 403);
        assertThat(probe.get("/advisor-policy/denied").status()).isIn(401, 403);
    }

    @Test
    void nativeRegexCorsReflectsOriginWithDefaultCredentialsAndAdvisorAgrees() {
        var probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        var response = probe.get("/advisor-policy/exact/child", Map.of("Origin", "https://unlisted.example"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo("https://unlisted.example");
        assertThat(response.header("Access-Control-Allow-Credentials")).isEqualTo("true");
        var scan = probe.post("/bootui/api/security/scan", Map.of("Content-Type", "application/json"));
        assertThat(scan.json().path("results"))
                .anySatisfy(result -> assertThat(result.path("id").asText()).isEqualTo("QS-CORS-002"));
    }

    @Test
    void nativeHttpAndRestPolicyPhasesCannotOverrideEachOthersDenial() {
        var probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        assertThat(probe.get("/advisor-policy/scopes/open").status()).isIn(401, 403);
        assertThat(probe.get("/advisor-policy/rest-scopes/open").status()).isIn(401, 403);
    }
}
