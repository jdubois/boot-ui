package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Real-boot check for the Quarkus Threads panel raw-dump download ({@code ThreadsResource} over the
 * shared engine {@code ThreadDumpService}). The passive {@code GET} is covered by the shared conformance
 * suite; this pins the state-changing {@code POST /download} action the panel's "Download dump" button
 * invokes so it is wired end-to-end on Quarkus rather than 404-ing: it returns the live thread dump as a
 * {@code text/plain} attachment. The same-origin loopback POST is accepted by the shared
 * {@code LocalhostGuard} write floor.
 */
@QuarkusTest
class BootUiQuarkusThreadsResourceTest {

    @TestHTTPResource
    URL baseUrl;

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl.toExternalForm());
    }

    @Test
    void downloadReturnsTheLiveThreadDumpAsPlainText() {
        Response response = probe().post("/bootui/api/threads/download", Map.of());

        assertThat(response.status()).as("POST /threads/download status").isEqualTo(200);
        assertThat(response.contentType())
                .as("the thread dump must be served as plain text (%s)", response.contentType())
                .startsWith("text/plain");
        assertThat(response.body())
                .as("the thread dump body must be the engine's rendered live-thread report")
                .startsWith("BootUI thread dump");
    }
}
