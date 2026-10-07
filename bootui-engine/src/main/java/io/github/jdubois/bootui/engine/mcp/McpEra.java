package io.github.jdubois.bootui.engine.mcp;

/**
 * The MCP protocol era a request is served in.
 *
 * <p>MCP 2026-07-28 calls a dual-era server one that answers both kinds of client on the same endpoint and "selects its
 * behavior from how the client opens": a request carrying modern per-request {@code _meta} is served statelessly, and an
 * {@code initialize} request selects legacy semantics. {@link McpEraResolver} makes that decision once per request.
 */
public enum McpEra {
    /** Initialization-based revisions (BootUI serves {@code 2025-06-18}); the wire shape is unchanged from BootUI 1.x. */
    LEGACY,
    /** Per-request metadata revisions (BootUI serves {@code 2026-07-28}). */
    MODERN
}
