package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.HttpProbeRequest;
import io.github.jdubois.bootui.core.BootUiDtos.HttpProbeResponse;
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
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/probe")
public class HttpProbeController {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final Environment environment;

    private final HttpClient httpClient;

    public HttpProbeController(Environment environment) {
        this.environment = environment;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    @PostMapping
    public HttpProbeResponse probe(@RequestBody HttpProbeRequest request) {
        long start = System.currentTimeMillis();
        String method = normalizeMethod(request == null ? null : request.method());
        String path = normalizePath(request == null ? null : request.path());
        String url = "http://localhost:" + resolveServerPort() + path;
        if (!url.startsWith("http://localhost:") && !url.startsWith("http://127.0.0.1:")) {
            return new HttpProbeResponse(0, "Forbidden", Map.of(), null, 0, "Only loopback probes allowed");
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT);
            applyHeaders(builder, request == null ? null : request.headers());
            builder.method(method, requestBodyPublisher(method, request == null ? null : request.body()));

            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = System.currentTimeMillis() - start;
            return new HttpProbeResponse(
                    response.statusCode(),
                    statusText(response.statusCode()),
                    filterHeaders(response.headers().map()),
                    response.body(),
                    durationMs,
                    null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long durationMs = System.currentTimeMillis() - start;
            return new HttpProbeResponse(0, "Error", Map.of(), null, durationMs, e.getMessage());
        } catch (IllegalArgumentException e) {
            long durationMs = System.currentTimeMillis() - start;
            return new HttpProbeResponse(0, "Error", Map.of(), null, durationMs, e.getMessage());
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

    private String resolveServerPort() {
        return environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
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
