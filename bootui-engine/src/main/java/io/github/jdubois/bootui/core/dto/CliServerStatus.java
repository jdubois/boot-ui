package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The answer to {@code GET /bootui/api/cli}: whether the command-line endpoint is available, the tool catalog
 * this instance exposes through it, and its call counters.
 *
 * <p>Answered even while the endpoint is disabled, so the CLI can report a precise reason rather than a bare
 * connection failure. When {@code enabled} is {@code false}, {@code tools} is empty.
 *
 * <p>The counters mirror the MCP server's, minus {@code responseLimitRefusals}: the command-line facade
 * applies no response byte budget, so that counter could only ever report zero.
 *
 * @param enabled whether tool invocation is currently accepted
 * @param serverName the server name, matching the MCP server's
 * @param serverVersion the BootUI version serving this endpoint
 * @param endpoint the relative path tools are invoked under
 * @param maxResults the {@code bootui.cli.max-results} cap applied to paginated read tools
 * @param callCount completed or timed-out tool calls since startup, counted separately from the MCP server's
 * @param totalLatencyMillis aggregate wall-clock latency of those calls
 * @param capacityRefusals calls refused because all execution slots were occupied
 * @param timeouts calls that exceeded the configured execution-time budget
 * @param toolCount the number of tools advertised
 * @param tools the advertised tools
 */
public record CliServerStatus(
        boolean enabled,
        String serverName,
        String serverVersion,
        String endpoint,
        int maxResults,
        long callCount,
        long totalLatencyMillis,
        long capacityRefusals,
        long timeouts,
        int toolCount,
        List<CliToolInfo> tools) {

    public CliServerStatus {
        tools = DtoCollections.immutableCopy(tools);
    }
}
