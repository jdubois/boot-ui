package io.github.jdubois.bootui.autoconfigure.mcp;

import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Describes a single MCP tool exposed by the BootUI MCP server.
 *
 * <p>Each tool maps to an existing BootUI panel so that the per-panel {@code bootui.panels.*}
 * enable and read-only toggles apply uniformly, exactly as they do for the browser UI and REST API.
 *
 * @param name machine name advertised to MCP clients (e.g. {@code architecture_scan})
 * @param description human-readable description shown to the agent
 * @param inputSchema JSON Schema for the tool arguments (an empty object schema when the tool takes
 *     no arguments)
 * @param panelId the {@code BootUiPanels} id backing this tool; used to enforce panel toggles
 * @param action {@code true} when the tool performs a panel action (state-changing); such tools are
 *     refused when the backing panel is read-only, mirroring {@code PanelAccessFilter}
 * @param handler maps validated arguments to a serializable result object (typically a BootUI DTO)
 */
public record McpTool(
        String name,
        String description,
        ObjectNode inputSchema,
        String panelId,
        boolean action,
        Function<JsonNode, Object> handler) {

    /** Invokes the tool handler with the supplied arguments (never {@code null}). */
    public Object invoke(JsonNode arguments) {
        return handler.apply(arguments);
    }
}
