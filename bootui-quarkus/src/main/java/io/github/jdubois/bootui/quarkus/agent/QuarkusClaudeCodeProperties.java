package io.github.jdubois.bootui.quarkus.agent;

import java.nio.file.Path;
import org.eclipse.microprofile.config.Config;

/** Claude Code session-store configuration over {@code bootui.claude-code.*} (raw reveal off). */
public class QuarkusClaudeCodeProperties extends QuarkusAgentSessionProperties {

    public QuarkusClaudeCodeProperties(Config config) {
        super("claude-code", config);
    }

    @Override
    public Path defaultSessionStateDir() {
        return home(".claude", "projects");
    }

    @Override
    public boolean isAllowRawReveal() {
        return false;
    }

    @Override
    public String getPanelTitle() {
        return "Claude Code";
    }

    @Override
    public String getSessionSourceName() {
        return "Claude Code";
    }

    @Override
    public String getWatcherThreadName() {
        return "bootui-claude-code-watcher";
    }

    @Override
    public boolean isProjectSessionDirectoryLayout() {
        return true;
    }
}
