package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Real-boot reachability checks for the Quarkus live Server-Sent-Events change-notification streams that
 * back the shared Vue panels' auto-refresh toggle: Live Activity, Security Logs and SQL Trace each expose
 * {@code GET .../stream} producing {@code text/event-stream}, mirroring the Log Tail and Exceptions streams
 * (and the Spring adapter's SSE endpoints). A missing stream would silently 404 and disable the toggle, so
 * we assert each one opens with a 200 + the SSE content type. The push payload itself (the {@code update}
 * tick on each recorded event) is pinned deterministically by the engine buffer unit tests.
 */
@QuarkusTest
class BootUiQuarkusLiveStreamResourceTest {

    @TestHTTPResource
    URL baseUrl;

    /**
     * Opens an SSE stream and returns the committed response without consuming the (open-ended) body:
     * {@link HttpResponse.BodyHandlers#ofInputStream()} returns as soon as the status line and headers
     * arrive (RESTEasy flushes the SSE headers immediately), so this never blocks on the live stream. The
     * body stream is closed right away so the server-side sink is released.
     */
    private HttpResponse<java.io.InputStream> openStream(String path) throws Exception {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.toExternalForm().replaceAll("/$", "") + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        response.body().close();
        return response;
    }

    private void assertSseStream(String path) throws Exception {
        HttpResponse<java.io.InputStream> response = openStream(path);
        assertThat(response.statusCode()).as("GET %s status", path).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .as("GET %s content-type", path)
                .contains("text/event-stream");
    }

    @Test
    void liveActivityStreamOpens() throws Exception {
        assertSseStream("/bootui/api/activity/stream");
    }

    @Test
    void securityLogsStreamOpens() throws Exception {
        assertSseStream("/bootui/api/security-logs/stream");
    }
}
