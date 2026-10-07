package io.github.jdubois.bootui.engine.mcp;

import java.util.Set;

/**
 * The MCP {@code tools/list} behavior hints for one tool, derived from its catalog entry so the three stacks cannot
 * describe the same tool differently.
 *
 * <p>Hints help an agent host decide when to ask the user before a call; they never replace BootUI's own per-panel
 * enable and read-only gates.
 *
 * @param readOnlyHint the tool does not change the application's state
 * @param destructiveHint the tool may discard state it cannot restore (only meaningful when not read-only)
 * @param idempotentHint calling it again with the same arguments has no additional effect
 * @param openWorldHint the tool may contact a network service outside the application and its local resources
 */
public record McpToolAnnotations(
        boolean readOnlyHint, boolean destructiveHint, boolean idempotentHint, boolean openWorldHint) {

    /** The actions that contact a service outside the application: OSV.dev for the vulnerability scan. */
    private static final Set<String> OPEN_WORLD = Set.of("vulnerabilities_scan");

    /**
     * The hints for one tool: reads are read-only and idempotent; a {@code clear_*} action discards buffered evidence;
     * clearing, pausing, and resuming are idempotent; every other action (a scan, a probe, a reload) is not.
     */
    public static McpToolAnnotations of(String name, boolean action) {
        if (!action) {
            return new McpToolAnnotations(true, false, true, false);
        }
        boolean destructive = name.startsWith("clear_");
        boolean idempotent = destructive || name.startsWith("pause_") || name.startsWith("resume_");
        return new McpToolAnnotations(false, destructive, idempotent, OPEN_WORLD.contains(name));
    }
}
