package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.spi.McpPanelPolicy;

/**
 * Quarkus {@link McpPanelPolicy}: the Quarkus adapter has no per-panel enable / read-only model yet
 * (there is no {@code PanelAccessFilter} equivalent), so every tool's backing panel is reported
 * enabled and never read-only. The shared {@code LocalhostGuard} write floor remains the only gate on
 * a state-changing MCP call here.
 *
 * <p>Panel <em>availability</em> is still enforced — but at the catalog level: {@code QuarkusMcpTools}
 * only advertises a tool when {@code QuarkusPanelAvailability.isPanelAvailable(panelId)} is true, so an
 * unavailable panel's tool never reaches this policy.
 */
public final class QuarkusMcpPanelPolicy implements McpPanelPolicy {

    @Override
    public boolean isEnabled(String panelId) {
        return true;
    }

    @Override
    public String disabledReason(String panelId) {
        return null;
    }

    @Override
    public boolean isReadOnly(String panelId) {
        return false;
    }

    @Override
    public String readOnlyReason(String panelId) {
        return null;
    }
}
