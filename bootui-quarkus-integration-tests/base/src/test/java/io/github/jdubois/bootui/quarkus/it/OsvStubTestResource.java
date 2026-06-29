package io.github.jdubois.bootui.quarkus.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Starts a loopback {@code com.sun.net.httpserver} stub standing in for OSV.dev and points the Quarkus
 * scanner at it via {@code bootui.vulnerabilities.osv-base-uri}, so the Vulnerabilities scan IT exercises
 * the real {@code OsvVulnerabilityScanner} HTTP/JSON path <strong>without ever touching the public OSV.dev
 * API</strong> (the standing rule for these tests).
 *
 * <p>The stub implements just enough of the OSV protocol: {@code POST /v1/querybatch} reports that the
 * <em>first</em> queried package (positional index 0) is affected by one advisory and all others are clean,
 * and {@code GET /v1/vulns/{id}} returns a single CRITICAL (CVSS 9.8) advisory. That guarantees exactly one
 * finding regardless of which dependency the build-time inventory happens to sort first, keeping the
 * assertions deterministic.</p>
 */
public class OsvStubTestResource implements QuarkusTestResourceLifecycleManager {

    static final String ADVISORY_ID = "GHSA-it-vuln-0001";

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start the loopback OSV stub", ex);
        }
        // Only the first queried package is reported vulnerable; the remainder come back clean.
        server.createContext(
                "/v1/querybatch",
                exchange -> respond(exchange, 200, "{\"results\":[{\"vulns\":[{\"id\":\"" + ADVISORY_ID + "\"}]}]}"));
        server.createContext(
                "/v1/vulns/",
                exchange -> respond(
                        exchange,
                        200,
                        "{\"id\":\"" + ADVISORY_ID + "\",\"summary\":\"Integration-test advisory\","
                                + "\"details\":\"A synthetic critical vulnerability used by the BootUI Quarkus IT.\","
                                + "\"aliases\":[\"CVE-2099-0001\"],"
                                + "\"severity\":[{\"type\":\"CVSS_V3\",\"score\":\"9.8\"}],"
                                + "\"references\":[{\"url\":\"https://example.test/advisory\"}]}"));
        server.start();
        return Map.of(
                "bootui.vulnerabilities.osv-base-uri",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
