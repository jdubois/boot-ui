package io.github.jdubois.bootui.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal, framework-neutral HTTP client used by the conformance suite. Built on the JDK
 * {@link HttpClient} so the shared suite adds no HTTP-client dependency and behaves identically
 * against the Spring Boot and Quarkus adapters.
 *
 * <p>Always bypasses any system proxy (the loopback test server must be reached directly) and never
 * throws on non-2xx responses, so callers can assert on the status code themselves.
 *
 * <p>A single probe instance keeps a cookie jar across requests, so a state-changing flow can prime a
 * cookie-based CSRF token with one request and have it sent back automatically on the next. The cookie
 * value is also readable via {@link #cookie(String)} so callers can echo it into a header (the Spring
 * SPA CSRF contract); the Quarkus adapter sets no such cookie, so the same flow runs unchanged there.
 */
public final class BootUiHttpProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final CookieManager cookieManager;
    private final HttpClient client;

    public BootUiHttpProbe(String baseUrl) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** GET {@code path} (relative to the base URL) with no extra headers. */
    public Response get(String path) {
        return get(path, Map.of());
    }

    /** GET {@code path} (relative to the base URL) with the supplied request headers. */
    public Response get(String path, Map<String, String> headers) {
        return request("GET", path, headers, null);
    }

    /** POST an empty body to {@code path} (relative to the base URL) with the supplied headers. */
    public Response post(String path, Map<String, String> headers) {
        return request("POST", path, headers, "");
    }

    /**
     * Send {@code method} to {@code path} (relative to the base URL) with the supplied headers and an
     * optional request body. Uses only non-restricted request headers so it behaves identically
     * across JDK versions.
     */
    public Response request(String method, String path, Map<String, String> headers, String body) {
        HttpRequest.BodyPublisher publisher =
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, publisher);
        headers.forEach(builder::header);
        return send(builder.build());
    }

    /**
     * Returns the current value of the named cookie if the server has set it on this probe's session.
     * Cookies persist for the life of the probe instance, so a request that primes a cookie must share
     * the same probe as the request that reads it.
     */
    public Optional<String> cookie(String name) {
        return cookieManager.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(
                    response.statusCode(),
                    response.headers().firstValue("content-type").orElse(""),
                    response.body());
        } catch (IOException ex) {
            throw new IllegalStateException("HTTP request failed: " + request.uri(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted: " + request.uri(), ex);
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** A captured HTTP response: status code, content-type header, and the raw body. */
    public record Response(int status, String contentType, String body) {

        public boolean isJson() {
            return contentType != null && contentType.toLowerCase().contains("json");
        }

        /** Parse the body as JSON, failing with a descriptive error if it is not valid JSON. */
        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (IOException ex) {
                throw new IllegalStateException("Response body is not valid JSON: " + preview(), ex);
            }
        }

        /** Parse the body as a JSON object into an insertion-ordered map of top-level fields. */
        public Map<String, JsonNode> jsonFields() {
            JsonNode node = json();
            Map<String, JsonNode> fields = new LinkedHashMap<>();
            node.fieldNames().forEachRemaining(name -> fields.put(name, node.get(name)));
            return fields;
        }

        private String preview() {
            if (body == null) {
                return "<null>";
            }
            return body.length() <= 200 ? body : body.substring(0, 200) + "...";
        }
    }
}
