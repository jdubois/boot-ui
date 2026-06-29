package io.github.jdubois.bootui.spi.agent;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Framework-neutral configuration for an agent session store (Copilot CLI, Claude Code). Each adapter
 * maps its own configuration onto this SPI: Spring over {@code BootUiProperties.Copilot}, Quarkus over
 * MicroProfile {@code Config}. Read once per store at construction (no UI/override path), so this is a
 * static settings contract rather than a live policy.
 */
public interface AgentSessionProperties {

    /** {@code true} when the panel is forced on regardless of directory presence. */
    boolean enabledOn();

    /** {@code true} when the panel is auto-enabled only if the session directory exists. */
    boolean enabledAuto();

    /** Configured session-state directory, or {@code null}/blank to use {@link #defaultSessionStateDir()}. */
    String getSessionStateDir();

    Path defaultSessionStateDir();

    int getMaxEventsPerSession();

    int getMaxSessions();

    int getMaxParsedSessions();

    Duration getStreamDebounce();

    boolean isAllowRawReveal();

    String getPanelTitle();

    String getSessionSourceName();

    String getWatcherThreadName();

    String maxParsedSessionsPropertyName();

    boolean isProjectSessionDirectoryLayout();
}
