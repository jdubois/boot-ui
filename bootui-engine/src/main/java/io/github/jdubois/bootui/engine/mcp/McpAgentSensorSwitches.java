package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.core.dto.JavaAgentReport;
import java.util.function.Supplier;

/** The sensor-switch result and expected refusals shared by all MCP/CLI adapter bindings. */
public final class McpAgentSensorSwitches {

    private McpAgentSensorSwitches() {}

    /** Applies the native panel switch, preserving its 400/409 refusals as in-band tool errors. */
    public static JavaAgentReport invoke(String id, Supplier<JavaAgentReport> switchSensor) {
        JavaAgentReport report;
        try {
            report = switchSensor.get();
        } catch (IllegalArgumentException ex) {
            throw new McpToolClientException(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new McpToolClientException(409, ex.getMessage());
        }
        return McpAgentViews.agentStatus(report, id);
    }
}
