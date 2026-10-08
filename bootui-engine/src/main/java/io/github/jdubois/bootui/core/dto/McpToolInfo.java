package io.github.jdubois.bootui.core.dto;

/**
 * Describes a single tool exposed by the BootUI MCP server, for display in the MCP Server panel.
 *
 * @param name machine name advertised to MCP clients (e.g. {@code architecture_scan})
 * @param description human-readable description shown to the agent
 * @param panel the {@code BootUiPanels} id backing this tool
 * @param action {@code true} when the tool performs a state-changing panel action
 * @param panelEnabled whether the backing panel is currently enabled
 * @param panelReadOnly whether the backing panel is currently read-only (action tools are refused)
 */
public record McpToolInfo(
        String name, String description, String panel, boolean action, boolean panelEnabled, boolean panelReadOnly) {}
