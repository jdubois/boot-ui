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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
                .version(HttpClient.Version.HTTP_1_1)
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

    /**
     * Starts a streaming GET and closes the body immediately after the response headers arrive.
     *
     * <p>This is intended for SSE conformance checks: waiting for the complete body would block forever
     * by design.</p>
     */
    public Response getStreaming(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<java.io.InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream ignored = response.body()) {
                return response(response, "");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("HTTP request failed: " + request.uri(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted: " + request.uri(), ex);
        }
    }

    /**
     * Starts a streaming GET, runs {@code afterOpen} once the response headers arrive, and returns the body received
     * until it holds the whole server-sent event containing {@code needle}, or {@code timeout} elapses, whichever comes
     * first. The stream is closed before returning, so an SSE endpoint's open-ended body never blocks the caller.
     */
    public String readStreamUntil(String path, Runnable afterOpen, String needle, Duration timeout) {
        return readStreamUntil(path, null, afterOpen, needle, timeout);
    }

    /**
     * Like {@link #readStreamUntil(String, Runnable, String, Duration)}, but runs {@code afterReady} only once the body
     * holds the whole event containing {@code readyNeedle}, for example the last replayed backlog line. Headers alone
     * do not prove the server has subscribed the client to live events, so an action that must be observed live waits
     * for that proof. A {@code null} {@code readyNeedle} runs {@code afterReady} right after the headers. The body is
     * read from the moment the headers arrive, and {@code timeout} bounds the wait for readiness and the needle together once the headers have arrived.
     */
    public String readStreamUntil(
            String path, String readyNeedle, Runnable afterReady, String needle, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        StreamBody received = new StreamBody();
        Thread reader = null;
        try {
            HttpResponse<java.io.InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long deadline = System.nanoTime() + timeout.toNanos();
            try (java.io.InputStream body = response.body()) {
                reader = new Thread(() -> received.readFrom(body), "bootui-conformance-stream-reader");
                reader.setDaemon(true);
                reader.start();
                if (readyNeedle == null || received.awaitEvent(readyNeedle, deadline)) {
                    afterReady.run();
                    received.awaitEvent(needle, deadline);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("HTTP request failed: " + request.uri(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted: " + request.uri(), ex);
        } finally {
            if (reader != null) {
                reader.interrupt();
            }
        }
        return received.text();
    }

    /** Whether {@code received} holds {@code needle} and the blank line that ends the event carrying it. */
    static boolean holdsCompleteEventWith(CharSequence received, String needle) {
        String text = received.toString().replace("\r\n", "\n").replace('\r', '\n');
        int found = text.indexOf(needle);
        return found >= 0 && text.indexOf("\n\n", found + needle.length()) >= 0;
    }

    /** The body received so far from one stream, filled by a reader thread and awaited by the caller. */
    private static final class StreamBody {

        private final StringBuilder received = new StringBuilder();
        private boolean ended;

        void readFrom(java.io.InputStream body) {
            try (java.io.Reader reader = new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8)) {
                char[] chunk = new char[8192];
                int read;
                while ((read = reader.read(chunk)) != -1) {
                    synchronized (this) {
                        received.append(chunk, 0, read);
                        notifyAll();
                    }
                }
            } catch (IOException ex) {
                // The caller closed the stream after its timeout; whatever arrived is returned.
            } finally {
                synchronized (this) {
                    ended = true;
                    notifyAll();
                }
            }
        }

        /** Waits until the body holds the whole event carrying {@code needle}; false once the stream ends or time is up. */
        synchronized boolean awaitEvent(String needle, long deadlineNanos) throws InterruptedException {
            while (!holdsCompleteEventWith(received, needle)) {
                long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
                if (ended || remainingMillis <= 0) {
                    return false;
                }
                wait(remainingMillis);
            }
            return true;
        }

        synchronized String text() {
            return received.toString();
        }
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
            return response(response, response.body());
        } catch (IOException ex) {
            throw new IllegalStateException("HTTP request failed: " + request.uri(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted: " + request.uri(), ex);
        }
    }

    private static Response response(HttpResponse<?> response, String body) {
        Map<String, List<String>> headers = response.headers().map().entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(Locale.ROOT),
                        entry -> List.copyOf(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new));
        return new Response(
                response.statusCode(),
                response.headers().firstValue("content-type").orElse(""),
                body,
                Collections.unmodifiableMap(headers));
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** A captured HTTP response: status code, content-type header, raw body, and response headers. */
    public record Response(int status, String contentType, String body, Map<String, List<String>> headers) {

        /** Backward-compatible constructor that uses an empty header map. */
        public Response(int status, String contentType, String body) {
            this(status, contentType, body, Map.of());
        }

        public boolean isJson() {
            return contentType != null && contentType.toLowerCase().contains("json");
        }

        /**
         * Returns the first value of the named response header (case-insensitive), or {@code null}
         * when the header is absent.
         */
        public String header(String name) {
            List<String> values = headerValues(name);
            return values.isEmpty() ? null : values.get(0);
        }

        /** Returns every value of the named response header, preserving duplicates for parity checks. */
        public List<String> headerValues(String name) {
            return name == null ? List.of() : headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }

        /** Parse the body as JSON, failing with a descriptive error if it is not valid JSON. */
        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (IOException ex) {
                throw new IllegalStateException("Response body is not valid JSON: " + preview(), ex);
            }
        }

        private String preview() {
            if (body == null) {
                return "<null>";
            }
            return body.length() <= 200 ? body : body.substring(0, 200) + "...";
        }
    }
}
