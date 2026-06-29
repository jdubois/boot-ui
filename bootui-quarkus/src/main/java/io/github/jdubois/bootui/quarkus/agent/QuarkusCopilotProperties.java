package io.github.jdubois.bootui.quarkus.agent;

import java.nio.file.Path;
import org.eclipse.microprofile.config.Config;

/** Copilot CLI session-store configuration over {@code bootui.copilot.*}. */
public class QuarkusCopilotProperties extends QuarkusAgentSessionProperties {

    private final Config config;

    public QuarkusCopilotProperties(Config config) {
        super("copilot", config);
        this.config = config;
    }

    @Override
    public Path defaultSessionStateDir() {
        return home(".copilot", "session-state");
    }

    @Override
    public boolean isAllowRawReveal() {
        return config.getOptionalValue("bootui.copilot.allow-raw-reveal", Boolean.class)
                .orElse(true);
    }

    @Override
    public String getPanelTitle() {
        return "Copilot";
    }

    @Override
    public String getSessionSourceName() {
        return "Copilot CLI";
    }

    @Override
    public String getWatcherThreadName() {
        return "bootui-copilot-watcher";
    }

    @Override
    public boolean isProjectSessionDirectoryLayout() {
        return false;
    }
}
