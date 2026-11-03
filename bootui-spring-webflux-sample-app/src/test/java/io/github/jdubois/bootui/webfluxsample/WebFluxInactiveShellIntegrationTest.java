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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * WebFlux twin of the servlet sample app's {@code BootUiInactiveShellIntegrationTests} (issue #856),
 * on a real embedded Netty with the real {@code bootui-ui} jar on the classpath.
 *
 * <p>{@code WebFluxAutoConfiguration} serves {@code classpath:/META-INF/resources/} by default just as
 * {@code WebMvcAutoConfiguration} does, so the packaged shell was reachable on this stack too while
 * BootUI was inactive. The reactive shell guard closes it identically.</p>
 */
@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "bootui.enabled=OFF",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-inactive-shell-test-overrides.properties"
        })
class WebFluxInactiveShellIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void packagedShellAndAssetsAreNotServedWhenBootUiIsInactive() throws Exception {
        assertThat(status("/bootui/index.html")).isEqualTo(404);
        assertThat(status("/bootui/")).isEqualTo(404);
        assertThat(status("/bootui")).isEqualTo(404);
        assertThat(status("/bootui/api/overview")).isEqualTo(404);
        // A real, content-hashed bundle entry resolved from the packaged jar: proves the 404 is the
        // guard rejecting an asset that genuinely exists, not just a missing file.
        assertThat(status("/bootui/assets/" + packagedAssetName())).isEqualTo(404);
        // PathPattern matches decoded segments, so the encoded spellings reach the same entry.
        assertThat(status("/%62ootui/index.html")).isEqualTo(404);
        assertThat(status("/bootui;version=1/index.html")).isEqualTo(404);
    }

    static String packagedAssetName() throws Exception {
        Resource[] assets = new PathMatchingResourcePatternResolver()
                .getResources("classpath:/META-INF/resources/bootui/assets/index-*.js");
        assertThat(assets).isNotEmpty();
        return assets[0].getFilename();
    }

    @Test
    void hostApplicationRoutesAreUntouched() throws Exception {
        // The guard sits at HIGHEST_PRECEDENCE, ahead of Spring Security, so this also proves it does
        // not swallow requests outside the reserved mount: /api/** is permitAll and still answers,
        // the welcome page stays public, and other paths still reach the security chain (401).
        assertThat(status("/api/greetings/bootui")).isEqualTo(200);
        assertThat(status("/")).isEqualTo(200);
        assertThat(status("/index.html")).isEqualTo(200);
        for (String asset : new String[] {"bootui-theme.css", "sample-lab.css", "sample-lab.js", "index.js"}) {
            assertThat(status("/" + asset)).as(asset).isEqualTo(200);
        }
        assertThat(status("/private")).isEqualTo(401);
        assertThat(status("/static/index.html")).isEqualTo(401);
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
