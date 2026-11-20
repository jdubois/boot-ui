package io.github.jdubois.bootui.engine.github;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class DefaultGitHubTokenProvider implements GitHubTokenProvider {

    private final Map<String, String> environment;

    private final ProcessStarter processStarter;

    DefaultGitHubTokenProvider() {
        this(System.getenv());
    }

    DefaultGitHubTokenProvider(Map<String, String> environment) {
        this(environment, ProcessBuilder::start);
    }

    DefaultGitHubTokenProvider(Map<String, String> environment, ProcessStarter processStarter) {
        this.environment = environment;
        this.processStarter = processStarter;
    }

    public static DefaultGitHubTokenProvider create() {
        return new DefaultGitHubTokenProvider();
    }

    @Override
    public Token token(Duration timeout) {
        return token("github.com", timeout);
    }

    @Override
    public Token token(String repositoryHost, Duration timeout) {
        String host = GitHubRepositoryDetector.credentialHost(repositoryHost);
        if (host.isBlank()) {
            return null;
        }
        List<String> tokenNames = "github.com".equals(host)
                ? List.of("GITHUB_TOKEN", "GH_TOKEN")
                : host.equals(GitHubRepositoryDetector.credentialHost(environment.get("GH_HOST")))
                        ? List.of("GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN")
                        : List.of();
        for (String name : tokenNames) {
            Token token = environmentToken(name);
            if (token != null) {
                return token;
            }
        }
        return ghCliToken(host, timeout);
    }

    private Token environmentToken(String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? null : new Token(value.trim(), name);
    }

    private Token ghCliToken(String host, Duration timeout) {
        // BootUI's own process: the BootUI agent's processes sensor must not show it as the application's.
        AgentBridgeAccess agent = AgentBridgeAccess.locate();
        boolean previous = agent.bootUiWork(true);
        try {
            return startGhCli(host, timeout);
        } finally {
            agent.bootUiWork(previous);
        }
    }

    private Token startGhCli(String host, Duration timeout) {
        Process process = null;
        try {
            // Resolve the GitHub CLI from the developer's PATH on purpose: BootUI is a localhost-only
            // dev tool and must invoke whichever "gh" the developer installed (Homebrew, apt, etc.),
            // so a fixed absolute path is intentionally not used.
            ProcessBuilder builder = new ProcessBuilder("gh", "auth", "token", "--hostname", host)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().putAll(environment);
            // gh also consults generic token variables with --hostname; use only its host-specific login.
            builder.environment()
                    .keySet()
                    .removeAll(List.of("GITHUB_TOKEN", "GH_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_ENTERPRISE_TOKEN"));
            builder.environment().put("GH_HOST", host);
            builder.environment().put("GH_PROMPT_DISABLED", "1");
            builder.environment().put("GH_NO_UPDATE_NOTIFIER", "1");
            process = processStarter.start(builder);
            boolean finished = process.waitFor(safeTimeout(timeout).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
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
                process.destroyForcibly();
            }
        }
    }

    private Duration safeTimeout(Duration timeout) {
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(5) : timeout;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }
}
