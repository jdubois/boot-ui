package io.github.jdubois.bootui.engine.mcp;

import java.util.List;

/**
 * Every value of each MCP request header, as the HTTP stack received them.
 *
 * <p>Adapters pass all values rather than the first, because Spring joins repeated headers while JAX-RS and Vert.x
 * return the first one: the engine rejects a repeated header instead of letting the stacks disagree on which copy the
 * body is checked against.
 *
 * @param protocolVersion the {@code MCP-Protocol-Version} values
 * @param method the {@code Mcp-Method} values
 * @param name the {@code Mcp-Name} values
 */
public record McpRequestHeaders(List<String> protocolVersion, List<String> method, List<String> name) {

    /** A request without any MCP header. */
    public static final McpRequestHeaders NONE = new McpRequestHeaders(List.of(), List.of(), List.of());

    public McpRequestHeaders {
        protocolVersion = copy(protocolVersion);
        method = copy(method);
        name = copy(name);
    }

    private static List<String> copy(List<String> values) {
        return values == null
                ? List.of()
                : values.stream().map(value -> value == null ? "" : value).toList();
    }
}
