package io.github.jdubois.bootui.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    /** Reads a BootUI panel endpoint directly, for the few commands that are not tool calls. */
    public JsonValue get(String apiPath) {
        String url = options.apiEndpoint(apiPath);
        HttpResponse<String> response = send(request(url).GET().build());
        requireSuccess(response);
        return parse(response.body(), url);
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
        int status = response.statusCode();
        ToolOutcome outcome = ToolOutcome.fromStatus(status);
        String body = response.body() == null ? "" : response.body();
        JsonValue payload;
        try {
            payload = body.isBlank() ? JsonValue.MISSING : JsonValue.parse(body);
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
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(url))
                .timeout(options.timeout())
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

    private String describeFailure(HttpResponse<String> response) {
        int status = response.statusCode();
        String detail = "";
        try {
            String message = JsonValue.parse(response.body()).get("error").asString(null);
            if (message != null) {
                detail = ": " + message;
            }
        } catch (RuntimeException notJson) {
            // A non-JSON error body carries nothing worth surfacing; the status already says what happened.
        }
        if (status == 401 || status == 403) {
            return "BootUI refused the request (HTTP " + status
                    + ")" + detail
                    + ". A non-loopback --url needs --token, and BootUI only answers requests it considers local.";
        }
        return "BootUI answered HTTP " + status + detail;
    }

    private static JsonValue parse(String body, String url) {
        try {
            return JsonValue.parse(body);
        } catch (JsonParseException notJson) {
            throw new BootUiClientException("Response from " + url + " is not JSON: " + notJson.getMessage(), notJson);
        }
    }

    @Override
    public void close() {
        // HttpClient holds an executor and selector; closing is a no-op before Java 21 but keeps callers
        // written the same way once this module can target a newer runtime.
    }
}
