package io.github.jdubois.bootui.spi;

/**
 * Resolves whether an MCP tool may be invoked, mirroring the per-panel enable / read-only toggles the
 * browser UI and REST API obey.
 *
 * <p>This is the framework-neutral gate behind {@code tools/call}. The Spring Boot adapter implements
 * it over {@code bootui.panels.*} (so a tool whose backing panel is disabled or whose action is
 * read-only is refused exactly like {@code PanelAccessFilter} blocks the equivalent HTTP request); the
 * Quarkus adapter, which has no per-panel enable/read-only model yet, implements it as always-enabled
 * and never-read-only — the shared {@code LocalhostGuard} write floor remains the only gate on a
 * state-changing MCP call there.
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
