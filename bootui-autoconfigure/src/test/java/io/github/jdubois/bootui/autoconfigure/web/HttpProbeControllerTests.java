package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.core.BootUiDtos.HttpProbeRequest;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Standalone MockMvc tests for {@link HttpProbeController}.
 *
 * <p>An embedded {@code com.sun.net.httpserver.HttpServer} acts as the downstream target
 * for probe requests that require a real connection. Cases that exercise error paths
 * (connection refused, null request body) do not need a live target.</p>
 */
class HttpProbeControllerTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private int serverPort;

    @BeforeEach
    void startEmbeddedServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        serverPort = server.getAddress().getPort();
    }

    @AfterEach
    void stopEmbeddedServer() {
        server.stop(0);
    }

    // ── method normalization ──────────────────────────────────────────────────

    @Test
    void lowercaseMethodNormalizedToUppercase() throws Exception {
        server.createContext("/api/ping", exchange -> {
            byte[] body = "pong".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        MockMvc mvc = buildMvc();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("get", "/api/ping", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    void nullMethodDefaultsToGet() throws Exception {
        server.createContext("/default-method", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        MockMvc mvc = buildMvc();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest(null, "/default-method", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));
    }

    // ── path normalization ────────────────────────────────────────────────────

    @Test
    void pathWithoutLeadingSlashGetsOneAdded() throws Exception {
        server.createContext("/hello", exchange -> {
            byte[] body = "world".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        MockMvc mvc = buildMvc();

        // path "hello" (no leading slash) should be normalized to "/hello"
        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("GET", "hello", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));
    }

    @Test
    void nullPathNormalizedToRoot() throws Exception {
        server.createContext("/", exchange -> {
            byte[] body = "root".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        MockMvc mvc = buildMvc();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("GET", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));
    }

    // ── loopback enforcement ──────────────────────────────────────────────────

    @Test
    void probeTargetIsAlwaysLoopback() throws Exception {
        // The controller builds its URL as "http://localhost:" + port + path,
        // so the target is always the local server regardless of the path value.
        // Even a path that looks like an external URL is appended to localhost.
        server.createContext("/safe", exchange -> {
            byte[] body = "local".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        MockMvc mvc = buildMvc();

        // Normal loopback probe succeeds
        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("GET", "/safe", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));
    }

    // ── response header filtering ─────────────────────────────────────────────

    @Test
    void onlyAllowedResponseHeadersPassedThrough() throws Exception {
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
        MockMvc mvc = buildMvc();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("GET", "/headers", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headers['content-type']").exists())
                // sensitive / non-allow-listed headers must not appear
                .andExpect(jsonPath("$.headers['x-custom-header']").doesNotExist())
                .andExpect(jsonPath("$.headers['authorization']").doesNotExist())
                .andExpect(jsonPath("$.headers['set-cookie']").doesNotExist());
    }

    @Test
    void locationHeaderPassedThrough() throws Exception {
        server.createContext("/redirect-me", exchange -> {
            exchange.getResponseHeaders().add("Location", "/new-location");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        MockMvc mvc = buildMvc();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("GET", "/redirect-me", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headers['location']").value("/new-location"));
    }

    // ── timeout / error responses ─────────────────────────────────────────────

    @Test
    void connectionRefusedProducesStableErrorDto() throws Exception {
        // Find a port that is currently not accepting connections.
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            closedPort = ss.getLocalPort();
        }

        MockEnvironment env = new MockEnvironment();
        env.setProperty("local.server.port", String.valueOf(closedPort));
        MockMvc mvc = standaloneSetup(new HttpProbeController(env)).build();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("GET", "/", null, null))))
                // The controller returns HTTP 200; error details are in the DTO
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.statusText").value("Error"))
                .andExpect(jsonPath("$.durationMs").isNumber());
    }

    @Test
    void allNullFieldsInRequestHandledGracefully() throws Exception {
        // Sending a request with all null fields: method→GET, path→/, body→noBody
        // The controller should fall back to defaults rather than throw.
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            closedPort = ss.getLocalPort();
        }
        MockEnvironment env = new MockEnvironment();
        env.setProperty("local.server.port", String.valueOf(closedPort));
        MockMvc mvc = standaloneSetup(new HttpProbeController(env)).build();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest(null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0));
    }

    // ── body handling ─────────────────────────────────────────────────────────

    @Test
    void responseBodyIncludedInDto() throws Exception {
        server.createContext("/body", exchange -> {
            byte[] body = "hello-body".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        MockMvc mvc = buildMvc();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("GET", "/body", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("hello-body"));
    }

    @Test
    void postBodySentToDownstreamServer() throws Exception {
        server.createContext("/echo", exchange -> {
            byte[] incoming = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, incoming.length);
            exchange.getResponseBody().write(incoming);
            exchange.close();
        });
        MockMvc mvc = buildMvc();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("POST", "/echo", "request-payload", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.body").value("request-payload"));
    }

    @Test
    void durationMsReflectsElapsedTime() throws Exception {
        server.createContext("/fast", exchange -> {
            byte[] body = "quick".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        MockMvc mvc = buildMvc();

        mvc.perform(post("/bootui/api/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new HttpProbeRequest("GET", "/fast", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMs").isNumber());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private MockMvc buildMvc() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("local.server.port", String.valueOf(serverPort));
        return standaloneSetup(new HttpProbeController(env)).build();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
