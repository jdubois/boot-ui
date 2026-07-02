package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.quarkus.QuarkusPanelAccessConfig;
import io.github.jdubois.bootui.spi.McpPanelPolicy;

/**
 * Quarkus {@link McpPanelPolicy}: gates an MCP {@code tools/call} on the same
 * {@code bootui.panels.*} enable / read-only toggles (plus the global {@code bootui.read-only}) that
 * the browser UI and {@code QuarkusPanelAccessFilter} obey, so a tool whose backing panel is disabled
 * (or whose action is read-only) is refused for identical reasons — mirroring the Spring adapter's
 * {@code SpringMcpPanelPolicy}, which delegates the same four methods straight to
 * {@code BootUiProperties}.
 *
 * <p>Panel <em>availability</em> is still enforced separately, at the catalog level: {@code
 * QuarkusMcpTools} only advertises a tool when {@code QuarkusPanelAvailability.isPanelAvailable(panelId)}
 * is true, so an unavailable panel's tool never reaches this policy.
 */
public final class QuarkusMcpPanelPolicy implements McpPanelPolicy {

    private final QuarkusPanelAccessConfig accessConfig;

    public QuarkusMcpPanelPolicy(QuarkusPanelAccessConfig accessConfig) {
        this.accessConfig = accessConfig;
    }

    @Override
    public boolean isEnabled(String panelId) {
        return accessConfig.isPanelEnabled(panelId);
    }

    @Override
    public String disabledReason(String panelId) {
        return accessConfig.panelDisabledReason(panelId);
    }

    @Override
    public boolean isReadOnly(String panelId) {
        return accessConfig.isPanelReadOnly(panelId);
    }

    @Override
    public String readOnlyReason(String panelId) {
        return accessConfig.panelReadOnlyReason(panelId);
    }
}
