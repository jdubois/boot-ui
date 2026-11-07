package io.github.jdubois.bootui.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Talks to one application's BootUI command-line endpoint.
 *
 * <p>The whole transport: read the catalog, invoke a tool, and call the handful of panel endpoints that are
 * not tool calls. Everything else — which commands exist, how results are rendered — belongs to the front-end
 * so the same client can serve the CLI and a build plugin.
 *
 * <p>Responses are opaque. Tool payloads are handed back as raw text plus a parsed tree, never bound to BootUI
 * DTO types, so a client compiled against one version keeps working against an application running another.
 */
public final class BootUiClient implements AutoCloseable {

    private static final String USER_AGENT = "bootui-cli";

    private final BootUiClientOptions options;
    private final HttpClient http;

    public BootUiClient(BootUiClientOptions options) {
        this(options, defaultHttpClient(options));
    }

    /**
     * Creates a client that never consults ambient proxy configuration.
     *
     * <p>Callers must still validate the destination before using this client. It is intended for bounded
     * loopback workflows where inheriting an HTTP proxy could send local diagnostic requests elsewhere.
     */
    public static BootUiClient direct(BootUiClientOptions options) {
        return new BootUiClient(options, directHttpClient(options));
    }

    BootUiClient(BootUiClientOptions options, HttpClient http) {
        this.options = Objects.requireNonNull(options, "options");
        this.http = Objects.requireNonNull(http, "http");
    }

    private static HttpClient defaultHttpClient(BootUiClientOptions options) {
        return HttpClient.newBuilder()
                // BootUI never redirects its API, and following one would be a way to leak the bearer token
                // to another origin.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout(options.timeout()))
                .build();
    }

    private static HttpClient directHttpClient(BootUiClientOptions options) {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout(options.timeout()))
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                        // There is no proxy to report to; the request path reports the connection failure.
                    }
                })
                .build();
    }

    private static Duration connectTimeout(Duration requestTimeout) {
        Duration budget = requestTimeout == null ? BootUiClientOptions.DEFAULT_TIMEOUT : requestTimeout;
        // Failing to connect should be reported immediately rather than consuming the whole request budget,
        // which exists for slow tools such as a dependency scan.
        return budget.compareTo(Duration.ofSeconds(10)) < 0 ? budget : Duration.ofSeconds(10);
    }

    /** The options this client was built with. */
    public BootUiClientOptions options() {
        return options;
    }

    /** Reads what the target instance advertises. */
    public BootUiCatalog catalog() {
        HttpResponse<String> response =
                send(request(options.cliEndpoint()).GET().build());
        if (response.statusCode() == 404) {
            throw new BootUiClientException(
                    "No BootUI command-line endpoint at " + options.cliEndpoint()
                            + ". The application may predate it, be running BootUI at a different --api-path, or not be a BootUI application.");
        }
        requireSuccess(response);
        return BootUiCatalog.from(parse(response.body(), options.cliEndpoint()));
    }

    /** Reads and validates the target catalog with a response-body byte limit. */
    public BootUiCatalog catalogBounded(int maxResponseBytes) {
        JsonValue document = getBounded("/cli", maxResponseBytes);
        return BootUiCatalog.from(document);
    }

    /** Reads the bounded catalog with a deadline remaining from a larger operation budget. */
    public BootUiCatalog catalogBounded(int maxResponseBytes, Duration remaining) {
        JsonValue document = getBounded("/cli", maxResponseBytes, remaining);
        return BootUiCatalog.from(document);
    }

    /** Invokes one tool with no arguments. */
    public ToolResult invoke(String toolName) {
        return invoke(toolName, null, null, null);
    }

    /**
     * Invokes one tool.
     *
     * <p>Only arguments the caller actually supplied are sent. The endpoint refuses any property the tool's
     * schema does not declare, so sending nulls for unused arguments would turn every call into a 400.
     *
     * @param toolName the tool to call
     * @param query the {@code query} filter, or {@code null}
     * @param limit the {@code limit} cap, or {@code null}
     * @param id the {@code id} of the resource, or {@code null}
     */
    public ToolResult invoke(String toolName, String query, Integer limit, String id) {
        Map<String, JsonValue> arguments = new LinkedHashMap<>();
        if (query != null) {
            arguments.put("query", JsonValue.of(query));
        }
        if (limit != null) {
            arguments.put("limit", JsonValue.of(limit.longValue()));
        }
        if (id != null) {
            arguments.put("id", JsonValue.of(id));
        }
        return invoke(toolName, arguments);
    }

    /**
     * Invokes a tool with its declared argument map, including advisor snapshot and paging arguments.
     * The server validates the schema; omit optional arguments rather than sending JSON null.
     */
    public ToolResult invoke(String toolName, Map<String, JsonValue> arguments) {
        HttpResponse<String> response = send(request(options.toolEndpoint(toolName))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonWriter.object(arguments)))
                .build());
        return toResult(toolName, response);
    }

    /** Invokes a tool with a response-body byte limit. */
    public ToolResult invokeBounded(String toolName, Map<String, JsonValue> arguments, int maxResponseBytes) {
        return invokeBounded(toolName, arguments, maxResponseBytes, options.timeout());
    }

    /** Invokes a tool with both a response-body cap and the caller's remaining operation deadline. */
    public ToolResult invokeBounded(
            String toolName, Map<String, JsonValue> arguments, int maxResponseBytes, Duration remaining) {
        BoundedResponse response = sendBounded(
                request(options.toolEndpoint(toolName), remaining)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JsonWriter.object(arguments)))
                        .build(),
                maxResponseBytes);
        return toResult(toolName, response.statusCode(), response.body(), true);
    }

    /** Reads a BootUI panel endpoint directly, for the few commands that are not tool calls. */
    public JsonValue get(String apiPath) {
        String url = options.apiEndpoint(apiPath);
        HttpResponse<String> response = send(request(url).GET().build());
        requireSuccess(response);
        return parse(response.body(), url);
    }

    /** Reads a panel endpoint while limiting the bytes consumed from its response. */
    public JsonValue getBounded(String apiPath, int maxResponseBytes) {
        return getBounded(apiPath, maxResponseBytes, options.timeout());
    }

    /** Reads a panel endpoint within the caller's remaining operation deadline and byte limit. */
    public JsonValue getBounded(String apiPath, int maxResponseBytes, Duration remaining) {
        String url = options.apiEndpoint(apiPath);
        BoundedResponse response = sendBounded(request(url, remaining).GET().build(), maxResponseBytes);
        if (response.statusCode() / 100 != 2) {
            throw new BootUiClientException(describeFailure(response.statusCode(), response.body()));
        }
        return parseStrict(response.body(), url);
    }

    /** Posts to a BootUI panel endpoint directly, for the few commands that are not tool calls. */
    public JsonValue post(String apiPath, String body) {
        String url = options.apiEndpoint(apiPath);
        HttpResponse<String> response = send(request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body))
                .build());
        requireSuccess(response);
        return response.body() == null || response.body().isBlank() ? JsonValue.MISSING : parse(response.body(), url);
    }

    private void requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new BootUiClientException(describeFailure(response));
        }
    }

    private ToolResult toResult(String toolName, HttpResponse<String> response) {
        return toResult(toolName, response.statusCode(), response.body());
    }

    private ToolResult toResult(String toolName, int status, String responseBody) {
        return toResult(toolName, status, responseBody, false);
    }

    private ToolResult toResult(String toolName, int status, String responseBody, boolean strictJson) {
        ToolOutcome outcome = ToolOutcome.fromStatus(status);
        String body = responseBody == null ? "" : responseBody;
        if (outcome.successful() && body.isBlank()) {
            throw new BootUiClientException(
                    "Response from " + options.toolEndpoint(toolName) + " does not contain JSON (HTTP " + status + ")");
        }
        JsonValue payload;
        try {
            payload = body.isBlank()
                    ? JsonValue.MISSING
                    : strictJson ? JsonValue.parseStrict(body) : JsonValue.parse(body);
        } catch (JsonParseException notJson) {
            // A non-JSON body from a successful call means something other than BootUI answered — a proxy or
            // a wrong --url. Say so rather than presenting an HTML page as a result.
            if (outcome.successful()) {
                throw new BootUiClientException(
                        "Response from " + options.toolEndpoint(toolName) + " is not JSON: " + notJson.getMessage(),
                        notJson);
            }
            payload = JsonValue.MISSING;
        }
        String error = outcome.successful() ? null : payload.get("error").asString(null);
        return new ToolResult(toolName, status, outcome, body, payload, error);
    }

    private HttpRequest.Builder request(String url) {
        return request(url, options.timeout());
    }

    private HttpRequest.Builder request(String url, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(url))
                .timeout(timeout.compareTo(options.timeout()) < 0 ? timeout : options.timeout())
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (options.token() != null) {
            request = request.header("Authorization", "Bearer " + options.token());
        }
        return request;
    }

    private static URI uri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException invalid) {
            throw new BootUiClientException("Not a valid URL: " + url, invalid);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException | ConnectException unreachable) {
            throw new BootUiClientException(
                    "Cannot reach BootUI at " + options.baseUrl()
                            + ". Is the application running, and is that the right --url?",
                    unreachable);
        } catch (HttpTimeoutException timeout) {
            throw new BootUiClientException(
                    "No response from " + options.baseUrl() + " within "
                            + options.timeout().toSeconds() + "s. Raise --timeout if the tool is expected to be slow.",
                    timeout);
        } catch (IOException failure) {
            throw new BootUiClientException(
                    "Request to " + options.baseUrl() + " failed: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BootUiClientException("Request to " + options.baseUrl() + " was interrupted", interrupted);
        }
    }

    private BoundedResponse sendBounded(HttpRequest request, int maxResponseBytes) {
        if (maxResponseBytes < 1 || maxResponseBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, ignored -> boundedBodySubscriber(maxResponseBytes));
        try {
            Duration timeout = request.timeout().orElse(options.timeout());
            long nanos;
            try {
                nanos = timeout.toNanos();
            } catch (ArithmeticException tooLarge) {
                nanos = Long.MAX_VALUE;
            }
            HttpResponse<byte[]> response = pending.get(nanos, TimeUnit.NANOSECONDS);
            String text;
            try {
                text = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(response.body()))
                        .toString();
            } catch (CharacterCodingException invalidUtf8) {
                throw new BootUiClientException("Response is not valid UTF-8", invalidUtf8);
            }
            return new BoundedResponse(response.statusCode(), text);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new BootUiClientException(
                    "Request to " + options.baseUrl() + " exceeded its response deadline.", timeout);
        } catch (ExecutionException failure) {
            if (hasCause(failure, ResponseTooLargeException.class)) {
                throw new BootUiClientException("Response exceeded the " + maxResponseBytes + " byte limit", failure);
            }
            if (hasCause(failure, HttpConnectTimeoutException.class) || hasCause(failure, ConnectException.class)) {
                throw new BootUiClientException(
                        "Cannot reach BootUI at " + options.baseUrl()
                                + ". Is the application running, and is that the right --url?",
                        failure);
            }
            if (hasCause(failure, HttpTimeoutException.class)) {
                throw new BootUiClientException(
                        "Request to " + options.baseUrl() + " exceeded its response deadline.", failure);
            }
            throw new BootUiClientException("Request to " + options.baseUrl() + " failed", failure);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new BootUiClientException("Request to " + options.baseUrl() + " was interrupted", interrupted);
        }
    }

    private static HttpResponse.BodySubscriber<byte[]> boundedBodySubscriber(int maxResponseBytes) {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> body = new CompletableFuture<>();
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxResponseBytes, 8192));
            private Flow.Subscription subscription;

            @Override
            public CompletionStage<byte[]> getBody() {
                return body;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                if (body.isDone()) {
                    return;
                }
                for (ByteBuffer buffer : buffers) {
                    if (buffer.remaining() > maxResponseBytes - bytes.size()) {
                        subscription.cancel();
                        body.completeExceptionally(new ResponseTooLargeException());
                        return;
                    }
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    bytes.writeBytes(chunk);
                }
                subscription.request(1);
            }

            @Override
            public void onError(Throwable failure) {
                body.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                body.complete(bytes.toByteArray());
            }
        };
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static final class ResponseTooLargeException extends IOException {}

    private record BoundedResponse(int statusCode, String body) {}

    private String describeFailure(HttpResponse<String> response) {
        return describeFailure(response.statusCode(), response.body());
    }

    private String describeFailure(int status, String responseBody) {
        String detail = "";
        boolean panelRefusal = false;
        try {
            JsonValue body = JsonValue.parse(responseBody);
            String error = nonblankString(body.get("error"));
            String reason = nonblankString(body.get("reason"));
            String message = nonblankString(body.get("message"));
            String specific = reason != null ? reason : message;
            if (error != null) detail = ": " + error;
            if (specific != null && !specific.equals(error)) detail += ": " + specific;
            panelRefusal = status == 403
                    && nonblankString(body.get("panel")) != null
                    && reason != null
                    && "BootUI panel access denied".equals(error);
        } catch (JsonParseException notJson) {
            // A non-JSON error body carries nothing worth surfacing; the status already says what happened.
        }
        if (status == 401 || (status == 403 && !panelRefusal)) {
            return "BootUI refused the request (HTTP " + status
                    + ")" + detail
                    + ". A non-loopback --url needs --token, and BootUI only answers requests it considers local.";
        }
        return "BootUI answered HTTP " + status + detail;
    }

    private static String nonblankString(JsonValue value) {
        String text = value.asString(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static JsonValue parse(String body, String url) {
        try {
            return JsonValue.parse(body);
        } catch (JsonParseException notJson) {
            throw new BootUiClientException("Response from " + url + " is not JSON: " + notJson.getMessage(), notJson);
        }
    }

    private static JsonValue parseStrict(String body, String url) {
        try {
            return JsonValue.parseStrict(body);
        } catch (JsonParseException notJson) {
            throw new BootUiClientException(
                    "Response from " + url + " is not strict JSON: " + notJson.getMessage(), notJson);
        }
    }

    @Override
    public void close() {
        // HttpClient holds an executor and selector; closing is a no-op before Java 21 but keeps callers
        // written the same way once this module can target a newer runtime.
    }
}
