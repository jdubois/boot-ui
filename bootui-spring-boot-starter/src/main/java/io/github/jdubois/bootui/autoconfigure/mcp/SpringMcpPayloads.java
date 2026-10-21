package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.engine.mcp.McpLowFootprint;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The same transport-only payload additions for MCP and the plain CLI facade. */
public final class SpringMcpPayloads {

    private SpringMcpPayloads() {}

    public static Object render(Object payload, ObjectMapper mapper) {
        if (!(payload instanceof McpLowFootprint.Result result)) {
            return payload;
        }
        if (!(mapper.valueToTree(result.payload()) instanceof ObjectNode object)) {
            throw new IllegalStateException("A low-footprint tool payload must be a JSON object");
        }
        object.set("lowFootprint", mapper.valueToTree(result.lowFootprint()));
        if (result.capturePaused() && object.has("capturing")) {
            object.put("capturing", false);
        }
        return object;
    }
}
