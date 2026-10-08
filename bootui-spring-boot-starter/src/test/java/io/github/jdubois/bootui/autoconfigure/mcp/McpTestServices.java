package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Builds an MCP service over explicit tools for tests outside this package. */
public final class McpTestServices {

    private McpTestServices() {}

    /** A service over {@code tools} whose failure reporter fails the test, since none is expected. */
    public static BootUiMcpService withTools(List<McpTool> tools, ObjectMapper objectMapper) {
        return new BootUiMcpService(tools, new BootUiProperties(), objectMapper, "1.2.3", (operation, failure) -> {
            throw new AssertionError("Unexpected server fault: " + operation, failure);
        });
    }
}
