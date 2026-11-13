package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.BootUiClientOptions;
import java.time.Duration;
import java.util.Map;

final class GlobalOptions {

    static final String URL_ENV = "BOOTUI_URL";
    static final String API_PATH_ENV = "BOOTUI_API_PATH";
    static final String TOKEN_ENV = "BOOTUI_TOKEN";

    private String url;
    private String apiPath;
    private String token;
    private String app;
    private Integer timeoutSeconds;
    private boolean json;
    private boolean noColor;
    private boolean verbose;

    void setUrl(String url) {
        this.url = url;
    }

    void setApiPath(String apiPath) {
        this.apiPath = apiPath;
    }

    void setToken(String token) {
        this.token = token;
    }

    void setApp(String app) {
        this.app = app;
    }

    String app() {
        return app;
    }

    boolean hasExplicitUrl() {
        return url != null && !url.isBlank();
    }

    boolean hasExplicitApiPath() {
        return apiPath != null && !apiPath.isBlank();
    }

    Duration timeout() {
        return timeoutSeconds == null ? BootUiClientOptions.DEFAULT_TIMEOUT : Duration.ofSeconds(timeoutSeconds);
    }

    String resolvedToken(Map<String, String> environment) {
        return first(token, environment.get(TOKEN_ENV), null);
    }

    void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    void setJson(boolean json) {
        this.json = json;
    }

    void setNoColor(boolean noColor) {
        this.noColor = noColor;
    }

    void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    boolean verbose() {
        return verbose;
    }

    boolean explicitJson() {
        return json;
    }

    boolean json(boolean terminal) {
        return json || !terminal;
    }

    boolean color(boolean terminal, Map<String, String> environment) {
        return terminal && !noColor && !environment.containsKey("NO_COLOR");
    }

    BootUiClientOptions toClientOptions(Map<String, String> environment) {
        return new BootUiClientOptions(
                first(url, environment.get(URL_ENV), BootUiClientOptions.DEFAULT_BASE_URL),
                first(apiPath, environment.get(API_PATH_ENV), BootUiClientOptions.DEFAULT_API_PATH),
                first(token, environment.get(TOKEN_ENV), null),
                timeoutSeconds == null ? BootUiClientOptions.DEFAULT_TIMEOUT : Duration.ofSeconds(timeoutSeconds));
    }

    private static String first(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
