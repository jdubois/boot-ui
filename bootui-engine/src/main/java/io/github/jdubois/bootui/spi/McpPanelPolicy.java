package io.github.jdubois.bootui.spi;

/**
 * Resolves whether an MCP tool may be invoked, mirroring the per-panel enable / read-only toggles the
 * browser UI and REST API obey.
 *
 * <p>This is the framework-neutral gate behind {@code tools/call}. Both adapters implement it over
 * {@code bootui.panels.*} (so a tool whose backing panel is disabled or whose action is read-only is
 * refused exactly like the equivalent HTTP request is blocked — {@code PanelAccessFilter} on Spring
 * Boot, {@code QuarkusPanelAccessFilter} on Quarkus) plus the global {@code bootui.read-only} switch;
 * the shared {@code LocalhostGuard} write floor remains an additional, independent gate on any
 * state-changing MCP call on both adapters.
 */
public interface McpPanelPolicy {

    /** Whether the panel backing a tool is enabled (a disabled panel refuses all of its tools). */
    boolean isEnabled(String panelId);

    /** Human-readable reason a disabled panel's tool was refused (reported in-band to the agent). */
    String disabledReason(String panelId);

    /** Whether the panel backing a tool is read-only (an action tool is refused when it is). */
    boolean isReadOnly(String panelId);

    /** Human-readable reason a read-only panel's action tool was refused (reported in-band). */
    String readOnlyReason(String panelId);
}
