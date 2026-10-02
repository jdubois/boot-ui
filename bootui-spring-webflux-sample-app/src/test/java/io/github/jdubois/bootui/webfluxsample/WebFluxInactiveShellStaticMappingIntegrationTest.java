package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * WebFlux twin of the servlet sample app's {@code BootUiInactiveShellStaticMappingIntegrationTests}
 * (issue #856): {@code spring.webflux.static-path-pattern} relocates the handler that exposes the
 * packaged bundle, so the shell guard has to follow it there.
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.enabled=OFF",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-inactive-shell-mapping-test-overrides.properties",
            "spring.webflux.static-path-pattern=/static/**"
        })
class WebFluxInactiveShellStaticMappingIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void relocatedStaticHandlingDoesNotExposeTheShell() throws Exception {
        // The sample app authenticates everything outside /api/**, /greeting/**, /actuator/** and /echo, so a
        // relocated host resource answers 401. The BootUI mount answers 404 instead: the guard runs
        // ahead of the security chain and rejects the request outright, which is exactly the
        // discriminator that shows the guard — not the security filter — handled it.
        assertThat(status("/static/index.html")).isEqualTo(401);

        assertThat(status("/static/bootui/index.html")).isEqualTo(404);
        assertThat(status("/static/bootui/assets/" + WebFluxInactiveShellIntegrationTest.packagedAssetName()))
                .isEqualTo(404);
        assertThat(status("/bootui/index.html")).isEqualTo(404);
    }

    private int status(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }
}
