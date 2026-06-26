package io.github.jdubois.bootui.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal, framework-neutral HTTP client used by the conformance suite. Built on the JDK
 * {@link HttpClient} so the shared suite adds no HTTP-client dependency and behaves identically
 * against the Spring Boot and Quarkus adapters.
 *
 * <p>Always bypasses any system proxy (the loopback test server must be reached directly) and never
 * throws on non-2xx responses, so callers can assert on the status code themselves.
 */
public final class BootUiHttpProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient client;

    public BootUiHttpProbe(String baseUrl) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
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
