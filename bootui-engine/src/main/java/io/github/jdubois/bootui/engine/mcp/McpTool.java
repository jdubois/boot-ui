package io.github.jdubois.bootui.engine.mcp;

import java.util.function.Function;

/**
 * A single MCP tool exposed by the BootUI MCP server.
 *
 * <p>Each tool maps to an existing BootUI panel so the per-panel enable / read-only toggles apply
 * uniformly (via the {@link io.github.jdubois.bootui.spi.McpPanelPolicy} SPI), exactly as they do for
 * the browser UI and REST API. The handler returns a serializable BootUI core DTO; the adapter
 * serializes it with its own {@code ObjectMapper}.
 *
 * @param name machine name advertised to MCP clients (e.g. {@code architecture_scan})
 * @param description human-readable description shown to the agent
 * @param schema the input-schema shape (the adapter renders the concrete JSON Schema)
 * @param panelId the {@code BootUiPanels} id backing this tool; used to enforce panel toggles
 * @param action {@code true} when the tool performs a panel action (state-changing); such tools are
 *     refused when the backing panel is read-only
 * @param handler maps normalized arguments to a serializable result object (typically a BootUI DTO)
 */
public record McpTool(
        String name,
        String description,
        McpToolSchema schema,
        String panelId,
        boolean action,
        Function<McpArguments, Object> handler) {

    /** Invokes the tool handler with the supplied normalized arguments. */
    public Object invoke(McpArguments arguments) {
        return handler.apply(arguments);
    }

    /** The advertised descriptor for this tool ({@code tools/list}). */
    public McpToolDescriptor describe() {
        return new McpToolDescriptor(name, description, schema);
    }
}
