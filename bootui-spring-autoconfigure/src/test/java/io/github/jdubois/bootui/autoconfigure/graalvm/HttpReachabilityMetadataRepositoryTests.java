package io.github.jdubois.bootui.autoconfigure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.engine.graalvm.Coordinates;
import io.github.jdubois.bootui.engine.graalvm.ReachabilityMetadataIndex;
import io.github.jdubois.bootui.engine.web.BoundedBodyReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the transport + JSON-deserialization behavior of the Spring adapter's
 * {@link io.github.jdubois.bootui.engine.graalvm.ReachabilityMetadataRepository} implementation against a
 * loopback {@link HttpServer}: the engine owns coverage policy, so this only asserts that a successful
 * response yields the raw rows, that the documented failure modes never throw, and that the request hits
 * the expected {@code groupId/artifactId/index.json} path.
 */
class HttpReachabilityMetadataRepositoryTests {

    private static final Coordinates POSTGRES = new Coordinates("org.postgresql", "postgresql", "42.7.11");

    @Test
    void successfulResponseIsParsedIntoRawIndexRows() throws Exception {
        Result result = fetch(
                POSTGRES,
                200,
                "[{\"metadata-version\":\"42.7.3\",\"tested-versions\":[\"42.7.3\",\"42.7.11\"],\"latest\":true}]");

        assertThat(result.index().lookupError()).isNull();
        assertThat(result.index().entries()).hasSize(1);
        ReachabilityMetadataIndex.Entry entry = result.index().entries().get(0);
        assertThat(entry.metadataVersion()).isEqualTo("42.7.3");
        assertThat(entry.testedVersions()).containsExactly("42.7.3", "42.7.11");
        assertThat(entry.latest()).isTrue();
        assertThat(result.requestedPath()).isEqualTo("/metadata/org.postgresql/postgresql/index.json");
    }

    @Test
    void notFoundReturnsAnEmptyIndexWithoutError() throws Exception {
        Result result = fetch(POSTGRES, 404, null);

        assertThat(result.index().entries()).isEmpty();
        assertThat(result.index().lookupError()).isNull();
    }

    @Test
    void serverErrorReportsUnavailableWithStatusCode() throws Exception {
        Result result = fetch(POSTGRES, 500, null);

        assertThat(result.index().entries()).isEmpty();
        assertThat(result.index().lookupError()).isEqualTo("HTTP 500");
    }

    @Test
    void nonArrayBodyReportsUnavailable() throws Exception {
        Result result = fetch(POSTGRES, 200, "{\"oops\":true}");

        assertThat(result.index().entries()).isEmpty();
        assertThat(result.index().lookupError()).isEqualTo("unexpected index format");
    }

    @Test
    void oversizedBodyReportsUnavailable() throws Exception {
        // Use a custom byte limit (10 bytes) to keep the test fast; serve a body that exceeds it.
        // The repository must not throw and must return an unavailable index with a clear message.
        String oversizedBody = "X".repeat(11); // 11 bytes > limit of 10
        Result result = fetchWithLimit(POSTGRES, 200, oversizedBody, 10);

        assertThat(result.index().entries()).isEmpty();
        assertThat(result.index().lookupError()).isNotNull();
        assertThat(result.index().lookupError()).contains("exceeds");
    }

    private record Result(ReachabilityMetadataIndex index, String requestedPath) {}

    private Result fetch(Coordinates coordinates, int status, String body) throws Exception {
        return fetchWithLimit(coordinates, status, body, BoundedBodyReader.GRAALVM_METADATA_MAX_BYTES);
    }

    private Result fetchWithLimit(Coordinates coordinates, int status, String body, int maxBodyBytes) throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metadata/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (payload.length == 0) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/metadata/";
            HttpReachabilityMetadataRepository repository = new HttpReachabilityMetadataRepository(
                    HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    base,
                    HttpReachabilityMetadataRepository.DEFAULT_TIMEOUT,
                    maxBodyBytes);
            return new Result(repository.fetch(coordinates), requestedPath.get());
        } finally {
            server.stop(0);
        }
    }
}
