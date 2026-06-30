package io.github.jdubois.bootui.quarkus.mcp;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the live on/off state of the BootUI MCP server on Quarkus so it can be toggled at runtime
 * from the "MCP Server" panel, overriding the configured {@code bootui.mcp.enabled} MicroProfile
 * Config value.
 *
 * <p>This is the Quarkus twin of the Spring {@code McpServerState}; it carries the configured mode as
 * a normalized {@code String} ({@code ON}/{@code OFF}/{@code AUTO}) since Quarkus has no Spring
 * {@code BootUiProperties.Mode} enum. The state is initialized from the configured mode: {@code ON}
 * starts the server enabled, while {@code OFF} and {@code AUTO} start it disabled (fail closed, never
 * silently exposed). The original configured mode is retained so the panel can show whether the
 * current state is an explicit override of configuration.
 *
 * <p>The bean is annotation-free and produced as a {@code @Singleton} by {@code BootUiMcpProducer}
 * (avoiding the CDI ambiguity of both declaring a scope and being {@code @Produces}d). The MCP
 * transport consults it on every request, so flipping it takes effect immediately without restarting
 * the application.
 */
public final class McpServerState {

    private final String configuredMode;
    private final AtomicBoolean enabled;

    public McpServerState(String configuredMode) {
        this.configuredMode = normalize(configuredMode);
        this.enabled = new AtomicBoolean("ON".equals(this.configuredMode));
    }

    private static String normalize(String mode) {
        if (mode == null) {
            return "OFF";
        }
        String trimmed = mode.trim().toUpperCase(Locale.ROOT);
        return trimmed.isEmpty() ? "OFF" : trimmed;
    }

    /** Whether the MCP server is currently serving requests. */
    public boolean isEnabled() {
        return enabled.get();
    }

    /** Sets the live state and returns the resulting value. */
    public boolean setEnabled(boolean value) {
        enabled.set(value);
        return value;
    }

    /** The {@code bootui.mcp.enabled} mode the application was configured with. */
    public String configuredMode() {
        return configuredMode;
    }

    /** Whether configuration started the server enabled ({@code ON}). */
    public boolean configuredEnabled() {
        return "ON".equals(configuredMode);
    }

    /** Whether the live state differs from the configured state (i.e. a runtime override is active). */
    public boolean overridden() {
        return enabled.get() != configuredEnabled();
    }
}
