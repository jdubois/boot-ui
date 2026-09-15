package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.BootUiClientOptions;
import java.time.Duration;
import java.util.Map;

/**
 * The options that apply to every command, wherever they appear on the line.
 *
 * <p>They are attached to each command rather than only to the root so that both {@code bootui --url … beans}
 * and {@code bootui beans --url …} work; people type the target last at least as often as first.
 */
final class GlobalOptions {

    static final String URL_ENV = "BOOTUI_URL";
    static final String API_PATH_ENV = "BOOTUI_API_PATH";
    static final String TOKEN_ENV = "BOOTUI_TOKEN";

    private String url;
    private String apiPath;
    private String token;
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

    void setTimeoutSeconds(Integer timeoutSeconds) {
        // The picocli converter (CommandTree.positiveSeconds) already rejects zero and negative values
        // before this setter is invoked, so there is nothing left to validate here.
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

    /**
     * Whether to emit the server's JSON verbatim.
     *
     * <p>Explicit {@code --json} always wins. Otherwise output is JSON when nothing looks like a terminal, so
     * {@code bootui beans | jq} works without a flag. That detection is a hint, not a guarantee — a JDK 22 or
     * later runtime reports a console even when redirected — so {@code --json} is the switch to rely on in a
     * script.
     */
    boolean json(boolean terminal) {
        return json || !terminal;
    }

    /** Whether to colour output; honours the {@code NO_COLOR} convention. */
    boolean color(boolean terminal, Map<String, String> environment) {
        return terminal && !noColor && !environment.containsKey("NO_COLOR");
    }

    /** The client options, filling unset flags from the environment and then from the defaults. */
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
