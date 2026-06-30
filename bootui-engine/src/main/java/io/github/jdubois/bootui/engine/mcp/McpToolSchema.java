package io.github.jdubois.bootui.engine.mcp;

/**
 * The shape of an MCP tool's input schema, advertised in {@code tools/list}.
 *
 * <p>The adapter renders the concrete JSON Schema from this enum so the engine stays JSON-free. The
 * three kinds cover every BootUI tool: action and plain read tools take no arguments ({@link #NONE}),
 * paged reads accept an optional {@code limit} ({@link #LIMIT}), and searchable reads accept an
 * optional {@code query} plus {@code limit} ({@link #QUERY_LIMIT}).
 */
public enum McpToolSchema {
    /** No arguments: an empty object schema. */
    NONE,
    /** An optional positive integer {@code limit}, capped by {@code bootui.mcp.max-results}. */
    LIMIT,
    /** An optional {@code query} string plus the optional {@code limit}. */
    QUERY_LIMIT
}
