package io.github.jdubois.bootui.conformance;

import java.util.List;

/**
 * Whether the JVM running a conformance test runs with the BootUI agent, and the surfaces that need it
 * ({@code docs/PLAN-v2.md} §5.13–§5.17). The sample applications' default runners run without it, so every detached
 * assertion here holds on the default CI legs; an agent-attached run skips them.
 */
final class JavaAgentPresence {

    /** The MCP tools, and their CLI commands, a stack advertises only while the agent's sensor records this start. */
    static final List<String> AGENT_SENSOR_TOOLS = List.of(
            "get_code_inventory", "get_code_paths", "start_method_probe", "get_method_probe", "get_side_effects");

    /** The Runtime Insights checks that need one of the agent's sensors, not applicable without it. */
    static final List<String> AGENT_CHECKS = List.of("changed-code-not-executed", "work-after-response");

    private JavaAgentPresence() {}

    /** Whether the agent's bridge is absent from the bootstrap class loader, where the agent appends it. */
    static boolean detached() {
        try {
            Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", false, null);
            return false;
        } catch (ClassNotFoundException | LinkageError ex) {
            return true;
        }
    }
}
