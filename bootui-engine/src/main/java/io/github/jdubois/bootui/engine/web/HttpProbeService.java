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
 * <p>Response bodies are bounded at {@link HttpProbeLimits#maxResponseBodyBytes()} (by default
 * {@link BoundedBodyReader#HTTP_PROBE_MAX_BYTES}): reading stops at that limit without first buffering
 * the full response, so a large or streaming local endpoint cannot destabilise the host JVM. When the
 * body is truncated, {@link HttpProbeResponse#truncated()} is {@code true} so the browser can surface
 * a clear truncation notice.
 *
 * <p>Inbound probe input is bounded too, by {@link HttpProbeLimits}: method, path, request body,
 * header count and header name/value sizes are validated in UTF-8 bytes <em>before</em> any outbound
 * work starts. Exceeding a limit is invalid input, not a probe outcome, so it fails with an
 * {@link IllegalArgumentException} that every adapter maps to the canonical {@code 400} with a
 * {@code {"error": ...}} body — unlike a genuine probe failure (connection refused, timeout), which is
 * reported inside a {@link HttpProbeResponse} with a {@code 0} status.
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

    private final HttpProbeLimits limits;

    public HttpProbeService(ServerPortSupplier serverPort) {
        this(serverPort, HttpProbeLimits.defaults());
    }

    public HttpProbeService(ServerPortSupplier serverPort, HttpProbeLimits limits) {
        this.serverPort = serverPort;
        this.limits = limits == null ? HttpProbeLimits.defaults() : limits;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    /**
     * Sends the probe and returns its sanitized outcome.
     *
     * @throws IllegalArgumentException if the request exceeds a {@link HttpProbeLimits} budget; the
     *     probe is then never sent, so no outbound work is done for rejected input
     */
    public HttpProbeResponse probe(HttpProbeRequest request) {
        validate(request);
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
                    builder.build(),
                    BoundedBodyReader.boundedBodyHandler(limits.maxResponseBodyBytes(), StandardCharsets.UTF_8));
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

    // ── inbound validation ────────────────────────────────────────────────────

    /**
     * Rejects a probe request that exceeds a {@link HttpProbeLimits} budget, before any outbound work
     * starts. Limits are checked in UTF-8 bytes, and the body is validated for every method (including
     * methods that cannot carry one), so an oversized payload is refused rather than silently dropped.
     * Messages never echo the offending input back to the browser.
     */
    private void validate(HttpProbeRequest request) {
        if (request == null) {
            return;
        }
        if (exceedsUtf8Bytes(request.method(), limits.maxMethodBytes())) {
            throw new IllegalArgumentException(
                    "HTTP Probe request method exceeds the maximum of " + limits.maxMethodBytes() + " bytes");
        }
        if (exceedsUtf8Bytes(request.path(), limits.maxPathBytes())) {
            throw new IllegalArgumentException(
                    "HTTP Probe request path exceeds the maximum of " + limits.maxPathBytes() + " bytes");
        }
        if (exceedsUtf8Bytes(request.body(), limits.maxRequestBodyBytes())) {
            throw new IllegalArgumentException(
                    "HTTP Probe request body exceeds the maximum of " + limits.maxRequestBodyBytes() + " bytes");
        }
        validateHeaders(request.headers());
    }

    private void validateHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        if (headers.size() > limits.maxHeaderCount()) {
            throw new IllegalArgumentException(
                    "HTTP Probe request exceeds the maximum of " + limits.maxHeaderCount() + " request headers");
        }
        int totalLimit = limits.maxTotalHeaderBytes();
        long total = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (exceedsUtf8Bytes(entry.getKey(), limits.maxHeaderNameBytes())) {
                throw new IllegalArgumentException("HTTP Probe request header name exceeds the maximum of "
                        + limits.maxHeaderNameBytes() + " bytes");
            }
            if (exceedsUtf8Bytes(entry.getValue(), limits.maxHeaderValueBytes())) {
                throw new IllegalArgumentException("HTTP Probe request header value exceeds the maximum of "
                        + limits.maxHeaderValueBytes() + " bytes");
            }
            total += utf8Bytes(entry.getKey(), totalLimit) + utf8Bytes(entry.getValue(), totalLimit);
            if (total > totalLimit) {
                throw new IllegalArgumentException(
                        "HTTP Probe request headers exceed the maximum total of " + totalLimit + " bytes");
            }
        }
    }

    private static boolean exceedsUtf8Bytes(String value, int limit) {
        return utf8Bytes(value, limit) > limit;
    }

    /**
     * Counts the UTF-8 size of {@code value}, stopping as soon as {@code cap} is exceeded (in which
     * case {@code cap + 1} is returned). Nothing is encoded or copied, so measuring an oversized value
     * costs a bounded scan instead of a full byte-array allocation.
     */
    private static long utf8Bytes(String value, int cap) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        long total = 0;
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char current = value.charAt(i);
            if (current < 0x80) {
                total += 1;
            } else if (current < 0x800) {
                total += 2;
            } else if (Character.isHighSurrogate(current)
                    && i + 1 < length
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                // a surrogate pair is a single supplementary code point: four UTF-8 bytes
                total += 4;
                i++;
            } else if (Character.isSurrogate(current)) {
                // an unpaired surrogate is malformed and encodes as a single replacement byte
                total += 1;
            } else {
                total += 3;
            }
            if (total > cap) {
                return cap + 1L;
            }
        }
        return total;
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        // Names that differ only by case are distinct map keys but the same HTTP header; the JDK builder
        // appends them as multiple values, which is valid and stays inside the validated header budgets.
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
