package io.github.jdubois.bootui.engine.mcp;

import java.util.Objects;

/**
 * The advertised description of a tool in a {@code tools/list} response.
 *
 * @param name machine name
 * @param description human-readable description
 * @param schema input-schema shape
 * @param inputSchema the per-tool argument schema the adapter renders to JSON Schema
 * @param annotations the behavior hints derived from the tool's catalog entry
 */
public record McpToolDescriptor(
        String name,
        String description,
        McpToolSchema schema,
        McpToolInputSchema inputSchema,
        McpToolAnnotations annotations) {

    public McpToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(annotations, "annotations");
    }

    /** BootUI tools always return structured JSON objects. */
    public String outputSchemaType() {
        return "object";
    }

    /** Human-readable guidance for the structured result. */
    public String outputSchemaDescription() {
        return "Structured BootUI result for " + name + ". Fields may evolve with the panel DTO contract.";
    }
}
