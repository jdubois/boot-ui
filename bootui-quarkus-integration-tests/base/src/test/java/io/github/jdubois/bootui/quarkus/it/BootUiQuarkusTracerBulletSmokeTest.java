package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Tracer-bullet smoke tests that pin the two Quarkus-specific goals not covered by the shared
 * conformance contract: that the shared Vue bundle is served at {@code /bootui/} straight from the
 * {@code bootui-ui} dependency jar (no build step on the consumer side), and that the Heap Dump panel —
 * which is "available" but whose primary data lives behind an action, so the shared suite skips it —
 * answers its GET with JSON from the engine {@code HeapDumpService}.
 */
@QuarkusTest
class BootUiQuarkusTracerBulletSmokeTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void sharedUiBundleIsServedAtBootuiRoot() {
        Response response = probe().get("/bootui/");
        assertThat(response.status())
                .as("GET /bootui/ should serve the shared Vue bundle's index.html from the bootui-ui jar")
                .isEqualTo(200);
        assertThat(response.contentType().toLowerCase())
                .as("GET /bootui/ content-type (%s)", response.contentType())
                .contains("text/html");
    }

    @Test
    void sharedUiBundleIsAlsoServedWithoutTrailingSlash() {
        // Quarkus' static-resource handler only answers /bootui/ (the directory index); without
        // QuarkusIndexResource, GET /bootui (no trailing slash) 404'd. See the class Javadoc.
        Response response = probe().get("/bootui");
        assertThat(response.status())
                .as("GET /bootui (no trailing slash) should serve the shared Vue bundle's index.html")
                .isEqualTo(200);
        assertThat(response.contentType().toLowerCase())
                .as("GET /bootui content-type (%s)", response.contentType())
                .contains("text/html");
        assertThat(response.body())
                .as("GET /bootui body should carry a <base href> so relative assets/API resolve")
                .contains("<base href=\"/bootui/\" />");
    }

    @Test
    void heapDumpPanelAnswersItsPrimaryGet() {
        Response response = probe().get("/bootui/api/heap-dump");
        assertThat(response.status()).as("GET /bootui/api/heap-dump status").isEqualTo(200);
        assertThat(response.isJson())
                .as("GET /bootui/api/heap-dump content-type (%s)", response.contentType())
                .isTrue();
    }
}
