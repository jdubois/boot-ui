package io.github.jdubois.bootui.autoconfigure.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

interface GitHubTokenProvider {

    Token token(Duration timeout);

    record Token(String value, String source) {}
}

final class DefaultGitHubTokenProvider implements GitHubTokenProvider {

    private final Map<String, String> environment;

    DefaultGitHubTokenProvider() {
        this(System.getenv());
    }

    DefaultGitHubTokenProvider(Map<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public Token token(Duration timeout) {
        Token environmentToken = environmentToken("GITHUB_TOKEN");
        if (environmentToken != null) {
            return environmentToken;
        }
        environmentToken = environmentToken("GH_TOKEN");
        if (environmentToken != null) {
            return environmentToken;
        }
        return ghCliToken(timeout);
    }

    private Token environmentToken(String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? null : new Token(value.trim(), name);
    }

    private Token ghCliToken(Duration timeout) {
        Process process = null;
        try {
            // Resolve the GitHub CLI from the developer's PATH on purpose: BootUI is a localhost-only
            // dev tool and must invoke whichever "gh" the developer installed (Homebrew, apt, etc.),
            // so a fixed absolute path is intentionally not used. (Reviewed: SonarCloud S4036.)
            process = new ProcessBuilder("gh", "auth", "token")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(safeTimeout(timeout).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return value.isBlank() ? null : new Token(value, "gh auth token");
        } catch (IOException ex) {
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private Duration safeTimeout(Duration timeout) {
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(5) : timeout;
    }
}
