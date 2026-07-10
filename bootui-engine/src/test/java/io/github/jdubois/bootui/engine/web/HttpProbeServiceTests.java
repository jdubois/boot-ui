package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.core.dto.HttpProbeRequest;
import io.github.jdubois.bootui.core.dto.HttpProbeResponse;
import io.github.jdubois.bootui.spi.ServerPortSupplier;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for the framework-neutral {@link HttpProbeService}. An embedded
 * {@code com.sun.net.httpserver.HttpServer} acts as the loopback target; the live port is supplied
 * through a fixed {@link ServerPortSupplier} double so the test stays framework-neutral (no Spring
 * environment binding). Error-path cases point the supplier at a closed port instead.
 */
class HttpProbeServiceTests {

    private HttpServer server;
    private HttpProbeService service;

    @BeforeEach
    void startEmbeddedServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        int port = server.getAddress().getPort();
        service = new HttpProbeService(() -> port);
    }

    @AfterEach
    void stopEmbeddedServer() {
        server.stop(0);
    }

    // ── method normalization ──────────────────────────────────────────────────

    @Test
    void lowercaseMethodNormalizedToUppercase() {
        respondWith("/api/ping", 200, "pong");

        HttpProbeResponse response = service.probe(new HttpProbeRequest("get", "/api/ping", null, null));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.error()).isNull();
    }

    @Test
    void nullMethodDefaultsToGet() {
        respondWith("/default-method", 200, "ok");

        HttpProbeResponse response = service.probe(new HttpProbeRequest(null, "/default-method", null, null));

        assertThat(response.status()).isEqualTo(200);
    }

    // ── path normalization ────────────────────────────────────────────────────

    @Test
    void pathWithoutLeadingSlashGetsOneAdded() {
        respondWith("/hello", 200, "world");

        // path "hello" (no leading slash) should be normalized to "/hello"
        HttpProbeResponse response = service.probe(new HttpProbeRequest("GET", "hello", null, null));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void nullPathNormalizedToRoot() {
        respondWith("/", 200, "root");

        HttpProbeResponse response = service.probe(new HttpProbeRequest("GET", null, null, null));

        assertThat(response.status()).isEqualTo(200);
    }

    // ── loopback enforcement ──────────────────────────────────────────────────

    @Test
    void probeTargetIsAlwaysLoopback() {
        // The service builds its URL as "http://localhost:" + port + path, so the target is always the
        // local server regardless of the path value.
        respondWith("/safe", 200, "local");

        HttpProbeResponse response = service.probe(new HttpProbeRequest("GET", "/safe", null, null));

        assertThat(response.status()).isEqualTo(200);
    }

    // ── response header filtering ─────────────────────────────────────────────

    @Test
    void onlyAllowedResponseHeadersPassedThrough() {
        server.createContext("/headers", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Custom-Header", "should-be-absent");
            exchange.getResponseHeaders().add("Authorization", "******");
            exchange.getResponseHeaders().add("Set-Cookie", "session=abc");
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        HttpProbeResponse response = service.probe(new HttpProbeRequest("GET", "/headers", null, null));

        assertThat(response.headers()).containsKey("content-type");
        assertThat(response.headers())
                .doesNotContainKey("x-custom-header")
                .doesNotContainKey("authorization")
                .doesNotContainKey("set-cookie");
    }

    @Test
    void locationHeaderPassedThrough() {
        server.createContext("/redirect-me", exchange -> {
            exchange.getResponseHeaders().add("Location", "/new-location");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        HttpProbeResponse response = service.probe(new HttpProbeRequest("GET", "/redirect-me", null, null));

        assertThat(response.headers()).containsEntry("location", "/new-location");
    }

    // ── timeout / error responses ─────────────────────────────────────────────

    @Test
    void connectionRefusedProducesStableErrorDto() throws Exception {
        int closed = closedPort();
        HttpProbeService refused = new HttpProbeService(() -> closed);

        HttpProbeResponse response = refused.probe(new HttpProbeRequest("GET", "/", null, null));

        assertThat(response.status()).isZero();
        assertThat(response.statusText()).isEqualTo("Error");
        assertThat(response.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void allNullFieldsInRequestHandledGracefully() throws Exception {
        // method->GET, path->/, body->noBody: defaults should apply rather than throw.
        int closed = closedPort();
        HttpProbeService refused = new HttpProbeService(() -> closed);

        HttpProbeResponse response = refused.probe(new HttpProbeRequest(null, null, null, null));

        assertThat(response.status()).isZero();
    }

    // ── body handling ─────────────────────────────────────────────────────────

    @Test
    void responseBodyIncludedInDto() {
        respondWith("/body", 200, "hello-body");

        HttpProbeResponse response = service.probe(new HttpProbeRequest("GET", "/body", null, null));

        assertThat(response.body()).isEqualTo("hello-body");
    }

    @Test
    void postBodySentToDownstreamServer() {
        server.createContext("/echo", exchange -> {
            byte[] incoming = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, incoming.length);
            exchange.getResponseBody().write(incoming);
            exchange.close();
        });

        HttpProbeResponse response = service.probe(new HttpProbeRequest("POST", "/echo", "request-payload", null));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("request-payload");
    }

    @Test
    void durationMsReflectsElapsedTime() {
        respondWith("/fast", 200, "quick");

        HttpProbeResponse response = service.probe(new HttpProbeRequest("GET", "/fast", null, null));

        assertThat(response.durationMs()).isGreaterThanOrEqualTo(0);
    }

    // ── request header handling ───────────────────────────────────────────────

    @Test
    void forwardsCustomRequestHeadersAndStripsRestrictedOnes() {
        server.createContext("/echo-headers", exchange -> {
            String custom = exchange.getRequestHeaders().getFirst("X-Custom");
            String host = exchange.getRequestHeaders().getFirst("Host");
            String body = "custom=" + custom + ";host=" + host;
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var headers = new LinkedHashMap<String, String>();
        headers.put("X-Custom", "forwarded");
        headers.put("Host", "evil.example.com");

        HttpProbeResponse response = service.probe(new HttpProbeRequest("GET", "/echo-headers", null, headers));

        assertThat(response.status()).isEqualTo(200);
        // custom header forwarded; the spoofed Host is stripped so it never reaches the target
        assertThat(response.body()).contains("custom=forwarded").doesNotContain("host=evil.example.com");
    }

    // ── truncation ────────────────────────────────────────────────────────────

    @Test
    void responseUnderLimitNotTruncated() {
        respondWith("/small", 200, "small-body");

        HttpProbeResponse response = service.probe(new HttpProbeRequest("GET", "/small", null, null));

        assertThat(response.body()).isEqualTo("small-body");
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void responseOverLimitIsTruncatedAndFlagSet() throws Exception {
        // Build a service with a very small limit so the test doesn't need a 1 MiB response.
        int limit = 5;
        HttpProbeService tinyLimit =
                new HttpProbeService(() -> server.getAddress().getPort(), limit);
        // Respond with exactly limit+1 bytes to trigger truncation.
        String longBody = "ABCDEF"; // 6 bytes > limit of 5
        respondWith("/big", 200, longBody);

        HttpProbeResponse response = tinyLimit.probe(new HttpProbeRequest("GET", "/big", null, null));

        assertThat(response.body()).isEqualTo("ABCDE"); // first 5 bytes
        assertThat(response.truncated()).isTrue();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.error()).isNull();
    }

    @Test
    void errorResponseHasTruncatedFalse() throws Exception {
        int closed = closedPort();
        HttpProbeService refused = new HttpProbeService(() -> closed);

        HttpProbeResponse response = refused.probe(new HttpProbeRequest("GET", "/", null, null));

        assertThat(response.truncated()).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void respondWith(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
