package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.HttpProbeRequest;
import io.github.jdubois.bootui.core.dto.HttpProbeResponse;
import io.github.jdubois.bootui.spi.ServerPortSupplier;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral HTTP Probe engine service: sends a request to the application's own loopback
 * address and returns a sanitized {@link HttpProbeResponse}.
 *
 * <p>The probe target is always {@code http://localhost:<port><path>}, so it can never reach an
 * external host regardless of the supplied path. The live local server port comes from a
 * {@link ServerPortSupplier} (read on every probe, since the bound port is only known once the server
 * is running). Hop-by-hop request headers are stripped, and only a small allow-list of response headers
 * is surfaced.
 *
 * <p>Response bodies are bounded at {@link BoundedBodyReader#HTTP_PROBE_MAX_BYTES}: reading stops at
 * that limit without first buffering the full response, so a large or streaming local endpoint cannot
 * destabilise the host JVM. When the body is truncated, {@link HttpProbeResponse#truncated()} is
 * {@code true} so the browser can surface a clear truncation notice.
 */
public class HttpProbeService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Hop-by-hop and connection-management headers that the {@link HttpClient}
     * manages itself. The JDK rejects attempts to set these, so a probe that
     * forwarded them verbatim would fail with an opaque error; they are stripped
     * instead so the rest of the request still goes through.
     */
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host",
            "connection",
            "content-length",
            "expect",
            "upgrade",
            "transfer-encoding",
            "proxy-connection",
            "keep-alive",
            "te");

    private final ServerPortSupplier serverPort;

    private final HttpClient httpClient;

    private final int maxBodyBytes;

    public HttpProbeService(ServerPortSupplier serverPort) {
        this(serverPort, BoundedBodyReader.HTTP_PROBE_MAX_BYTES);
    }

    /** Package-private constructor for testing with a custom byte limit. */
    HttpProbeService(ServerPortSupplier serverPort, int maxBodyBytes) {
        this.serverPort = serverPort;
        this.maxBodyBytes = maxBodyBytes;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    public HttpProbeResponse probe(HttpProbeRequest request) {
        long start = System.currentTimeMillis();
        String method = normalizeMethod(request == null ? null : request.method());
        String path = normalizePath(request == null ? null : request.path());
        String url = "http://localhost:" + serverPort.localServerPort() + path;

        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
            applyHeaders(builder, request == null ? null : request.headers());
            builder.method(method, requestBodyPublisher(method, request == null ? null : request.body()));

            HttpResponse<BoundedBodyReader.BoundedRead> response = httpClient.send(
                    builder.build(), BoundedBodyReader.boundedBodyHandler(maxBodyBytes, StandardCharsets.UTF_8));
            long durationMs = System.currentTimeMillis() - start;
            BoundedBodyReader.BoundedRead read = response.body();
            return new HttpProbeResponse(
                    response.statusCode(),
                    statusText(response.statusCode()),
                    filterHeaders(response.headers().map()),
                    read.body(),
                    durationMs,
                    null,
                    read.truncated());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long durationMs = System.currentTimeMillis() - start;
            return new HttpProbeResponse(0, "Error", Map.of(), null, durationMs, e.getMessage(), false);
        } catch (IllegalArgumentException e) {
            long durationMs = System.currentTimeMillis() - start;
            return new HttpProbeResponse(0, "Error", Map.of(), null, durationMs, e.getMessage(), false);
        }
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (RESTRICTED_HEADERS.contains(entry.getKey().trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            builder.header(entry.getKey(), entry.getValue());
        }
    }

    private HttpRequest.BodyPublisher requestBodyPublisher(String method, String body) {
        if (!allowsBody(method)) {
            return HttpRequest.BodyPublishers.noBody();
        }
        if (body == null || body.isEmpty()) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
    }

    private boolean allowsBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return "GET";
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private Map<String, String> filterHeaders(Map<String, java.util.List<String>> headers) {
        Map<String, String> filtered = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name == null || values == null || values.isEmpty()) {
                return;
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!"content-type".equals(normalized)
                    && !"content-length".equals(normalized)
                    && !"location".equals(normalized)) {
                return;
            }
            filtered.put(normalized, String.join(", ", values));
        });
        return filtered;
    }

    private String statusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "HTTP " + status;
        };
    }
}
