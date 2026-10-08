package io.github.jdubois.bootui.conformance;

import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolAnnotations;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptor;
import io.github.jdubois.bootui.engine.mcp.McpToolInputSchema;
import java.util.List;
import java.util.function.Function;

/**
 * The cross-codec parity contract for {@code tools/list}: the Spring (Jackson 3) and Quarkus (Jackson 2) MCP codecs
 * must render every tool's {@code inputSchema} and {@code annotations} to the same bytes.
 *
 * <p>Each codec test lists {@link #tools(Function)} through its own codec at {@link #MAX_RESULTS}, projects every
 * advertised tool to {@code {"name","inputSchema","annotations"}} with its own {@code ObjectMapper}, and compares the
 * compact JSON array with {@link #expected()}. Tool descriptions are left out: they differ by stack on purpose.
 */
public final class McpCodecParity {

    /** The {@code max-results} cap both codec tests list the catalog with, which sets the advertised defaults. */
    public static final int MAX_RESULTS = 250;

    private McpCodecParity() {}

    /** Every catalog tool, with the stack's description and a handler that is never called. */
    public static List<McpTool> tools(Function<String, String> description) {
        return McpToolCatalog.entries().stream()
                .map(entry -> new McpTool(
                        entry.name(),
                        description.apply(entry.name()),
                        entry.schema(),
                        entry.panelId(),
                        entry.action(),
                        arguments -> {
                            throw new AssertionError("tools/list must not invoke " + entry.name());
                        }))
                .toList();
    }

    /**
     * The compact JSON both codecs must produce: one {@code {"name","inputSchema","annotations"}} object per catalog
     * tool, in catalog order, rendered here from the engine's own records rather than by either codec.
     */
    public static String expected() {
        StringBuilder json = new StringBuilder("[");
        List<McpTool> tools = tools(name -> "");
        for (int i = 0; i < tools.size(); i++) {
            McpToolDescriptor tool = tools.get(i).describe(MAX_RESULTS);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"name\":").append(string(tool.name()));
            json.append(",\"inputSchema\":").append(inputSchema(tool.inputSchema()));
            json.append(",\"annotations\":").append(annotations(tool.annotations()));
            json.append('}');
        }
        return json.append(']').toString();
    }

    private static String inputSchema(McpToolInputSchema schema) {
        StringBuilder json = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        List<McpToolInputSchema.Property> properties = schema.properties();
        for (int i = 0; i < properties.size(); i++) {
            McpToolInputSchema.Property property = properties.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append(string(property.name())).append(":{\"type\":").append(string(property.type()));
            if (property.minimum() != null) {
                json.append(",\"minimum\":").append(property.minimum());
            }
            if (property.minLength() != null) {
                json.append(",\"minLength\":").append(property.minLength());
            }
            if (property.defaultValue() != null) {
                json.append(",\"default\":").append(property.defaultValue());
            }
            json.append(",\"description\":").append(string(property.description()));
            if (!property.examples().isEmpty()) {
                json.append(",\"examples\":[");
                for (int e = 0; e < property.examples().size(); e++) {
                    json.append(e > 0 ? "," : "")
                            .append(string(property.examples().get(e)));
                }
                json.append(']');
            }
            json.append('}');
        }
        json.append('}');
        if (!schema.required().isEmpty()) {
            json.append(",\"required\":[");
            for (int r = 0; r < schema.required().size(); r++) {
                json.append(r > 0 ? "," : "").append(string(schema.required().get(r)));
            }
            json.append(']');
        }
        return json.append(",\"additionalProperties\":false}").toString();
    }

    private static String annotations(McpToolAnnotations hints) {
        return "{\"readOnlyHint\":" + hints.readOnlyHint() + ",\"destructiveHint\":" + hints.destructiveHint()
                + ",\"idempotentHint\":" + hints.idempotentHint() + ",\"openWorldHint\":" + hints.openWorldHint()
                + "}";
    }

    /** {@code value} as a JSON string, escaped the way both Jackson versions write it. */
    private static String string(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04X", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
