package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties.Mode;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the live on/off state of the BootUI MCP server so it can be toggled at runtime from the
 * "MCP Server" panel, overriding the configured {@code bootui.mcp.enabled} Spring Boot property.
 *
 * <p>The state is initialized from the configured {@link Mode}: {@link Mode#ON} starts the server
 * enabled, while {@link Mode#OFF} and {@link Mode#AUTO} start it disabled (fail closed, never
 * silently exposed). The original configured mode is retained so the panel can show whether the
 * current state is an explicit override of configuration.
 *
 * <p>This bean is always registered while BootUI is active; the MCP transport and tools consult it
 * on every request, so flipping it takes effect immediately without restarting the application.
 */
public final class McpServerState {

    private final Mode configuredMode;
    private final AtomicBoolean enabled;

    public McpServerState(Mode configuredMode) {
        this.configuredMode = configuredMode == null ? Mode.OFF : configuredMode;
        this.enabled = new AtomicBoolean(this.configuredMode == Mode.ON);
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
    public Mode configuredMode() {
        return configuredMode;
    }

    /** Whether configuration started the server enabled ({@link Mode#ON}). */
    public boolean configuredEnabled() {
        return configuredMode == Mode.ON;
    }

    /** Whether the live state differs from the configured state (i.e. a runtime override is active). */
    public boolean overridden() {
        return enabled.get() != configuredEnabled();
    }
}
