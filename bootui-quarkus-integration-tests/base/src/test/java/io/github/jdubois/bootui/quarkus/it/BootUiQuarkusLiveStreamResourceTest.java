package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Real-boot checks for the Quarkus live Server-Sent-Events change-notification streams that back the shared
 * Vue panels' auto-refresh toggle. All five data panels that auto-refresh — Live Activity, Security Logs,
 * SQL Trace, Exceptions and Log Tail — expose {@code GET .../stream} producing {@code text/event-stream}
 * (mirroring the Spring adapter's SSE endpoints). A missing stream would silently 404 and disable the
 * toggle, so each is asserted to open with a 200 + the SSE content type.
 *
 * <p>Log Tail additionally carries a {@code LogLineDto} payload (a named {@code log} event) rather than a
 * bare {@code update} tick, so it gets a stronger end-to-end check: a line logged <em>before</em> the
 * stream opens must arrive via the buffer's backlog replay, proving the rewritten {@code Multi}-based
 * stream delivers named events and replays the backlog to new subscribers.
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
    private HttpResponse<InputStream> openStream(String path) throws Exception {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.toExternalForm().replaceAll("/$", "") + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        response.body().close();
        return response;
    }

    private void assertSseStream(String path) throws Exception {
        HttpResponse<InputStream> response = openStream(path);
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

    @Test
    void sqlTraceStreamOpens() throws Exception {
        assertSseStream("/bootui/api/sql-trace/stream");
    }

    @Test
    void exceptionsStreamOpens() throws Exception {
        assertSseStream("/bootui/api/exceptions/stream");
    }

    @Test
    void logTailStreamReplaysBufferedLinesAsNamedLogEvents() throws Exception {
        // Logged BEFORE the stream opens, so the line lives in the buffer and must arrive via backlog replay.
        String marker = "logtail-stream-marker-" + UUID.randomUUID();
        Logger.getLogger("com.example.quarkus.LogTailStreamProbe").warning(marker);

        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.toExternalForm().replaceAll("/$", "") + "/bootui/api/log-tail/stream"))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode())
                .as("GET /bootui/api/log-tail/stream status")
                .isEqualTo(200);

        try (InputStream body = response.body()) {
            String received = readUntilMarker(body, marker, Duration.ofSeconds(10));
            assertThat(received)
                    .as("the log-tail stream must replay the buffered line as a named 'log' event")
                    .contains("event:log")
                    .contains(marker);
        }
    }

    /**
     * Reads the SSE body line by line on a worker thread until a line containing {@code marker} is seen or
     * {@code timeout} elapses, so a never-arriving line fails fast instead of hanging on the open stream.
     */
    private static String readUntilMarker(InputStream body, String marker, Duration timeout) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(() -> {
                StringBuilder seen = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    seen.append(line).append('\n');
                    if (line.contains(marker)) {
                        break;
                    }
                }
                return seen.toString();
            });
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
