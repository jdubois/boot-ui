package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.spi.McpPanelPolicy;

/**
 * Spring {@link McpPanelPolicy}: gates an MCP {@code tools/call} on the same {@code bootui.panels.*}
 * enable / read-only toggles the browser UI and {@code PanelAccessFilter} obey, so a tool whose
 * backing panel is disabled (or whose action is read-only) is refused for identical reasons.
 */
public final class SpringMcpPanelPolicy implements McpPanelPolicy {

    private final BootUiProperties properties;

    public SpringMcpPanelPolicy(BootUiProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isEnabled(String panelId) {
        return properties.isPanelEnabled(panelId);
    }

    @Override
    public String disabledReason(String panelId) {
        return properties.panelDisabledReason(panelId);
    }

    @Override
    public boolean isReadOnly(String panelId) {
        return properties.isPanelReadOnly(panelId);
    }

    @Override
    public String readOnlyReason(String panelId) {
        return properties.panelReadOnlyReason(panelId);
    }
}
