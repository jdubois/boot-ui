package io.github.jdubois.bootui.engine.mcp;

/**
 * The advertised description of a tool in a {@code tools/list} response.
 *
 * @param name machine name
 * @param description human-readable description
 * @param schema input-schema shape the adapter renders to JSON Schema
 */
public record McpToolDescriptor(String name, String description, McpToolSchema schema) {

    /** BootUI tools always return structured JSON objects. */
    public String outputSchemaType() {
        return "object";
    }
}
