package io.github.jdubois.bootui.quarkus.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpLowFootprint;

/** The same transport-only payload additions for MCP and the plain CLI facade. */
public final class QuarkusMcpPayloads {

    private QuarkusMcpPayloads() {}

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
