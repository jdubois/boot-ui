package io.github.jdubois.bootui.client;

import java.time.Duration;

/**
 * Where to reach a BootUI instance and how.
 *
 * @param baseUrl the application's base URL, e.g. {@code http://localhost:8080}
 * @param apiPath the BootUI API path, which {@code bootui.api-path} makes configurable per application
 * @param token the {@code bootui.authentication.token} value, or {@code null} when the app requires none
 * @param timeout the per-request budget
 */
public record BootUiClientOptions(String baseUrl, String apiPath, String token, Duration timeout) {

    /** The default application URL, matching Spring Boot's and Quarkus' own default port. */
    public static final String DEFAULT_BASE_URL = "http://localhost:8080";

    /** The default value of {@code bootui.api-path}. */
    public static final String DEFAULT_API_PATH = "/bootui/api";

    /** Generous enough for a scan, short enough that a wedged call does not hang a build. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public BootUiClientOptions {
        baseUrl = normalizeBaseUrl(baseUrl);
        apiPath = normalizeApiPath(apiPath);
        token = token == null || token.isBlank() ? null : token.trim();
        timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
    }

    /** Options for a locally running application with no authentication token. */
    public static BootUiClientOptions defaults() {
        return new BootUiClientOptions(DEFAULT_BASE_URL, DEFAULT_API_PATH, null, DEFAULT_TIMEOUT);
    }

    /** The absolute URL of the command-line endpoint. */
    public String cliEndpoint() {
        return baseUrl + apiPath + "/cli";
    }

    /** The absolute URL that invokes one tool. */
    public String toolEndpoint(String toolName) {
        return cliEndpoint() + "/tools/" + toolName;
    }

    /** The absolute URL of a BootUI panel endpoint, for the few commands that are not tool calls. */
    public String apiEndpoint(String path) {
        return baseUrl + apiPath + (path.startsWith("/") ? path : "/" + path);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        if (value.startsWith(":")) {
            // ':9000' is how people write "same host, other port"; without a host the URI has no authority
            // and the request would fail deep in the HTTP client.
            value = "localhost" + value;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            // A bare host:port is what people actually type; guessing http is friendlier than a usage error.
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String normalizeApiPath(String apiPath) {
        String value = apiPath == null || apiPath.isBlank() ? DEFAULT_API_PATH : apiPath.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? DEFAULT_API_PATH : value;
    }

    /** Redacts the token, so logging these options cannot leak a credential. */
    @Override
    public String toString() {
        return "BootUiClientOptions[baseUrl=" + baseUrl + ", apiPath=" + apiPath + ", token="
                + (token == null ? "none" : "******") + ", timeout=" + timeout + "]";
    }
}
