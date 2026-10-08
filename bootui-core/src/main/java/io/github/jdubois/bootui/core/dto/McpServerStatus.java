package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Status of the local, opt-in BootUI MCP (Model Context Protocol) server, surfaced to the MCP
 * Server panel.
 *
 * @param enabled whether the server is currently serving JSON-RPC requests (live runtime state)
 * @param configuredMode the configured {@code bootui.mcp.enabled} mode ({@code ON}/{@code OFF}/{@code AUTO})
 * @param overridden whether the live state differs from the configured state (a runtime override)
 * @param serverName the MCP server name advertised to clients
 * @param serverVersion the BootUI version reported in the MCP handshake
 * @param transport the transport exposed (always {@code http} for the loopback endpoint)
 * @param endpoint the relative JSON-RPC endpoint path
 * @param protocolVersion the MCP protocol revision a legacy client negotiates through {@code initialize}
 * @param supportedProtocolVersions every revision the endpoint serves, newest first (MCP 2026-07-28 and 2025-06-18)
 * @param maxResults the {@code bootui.mcp.max-results} cap applied to paginated read tools
 * @param callCount tool calls that ended since server startup, completed, timed out, or cancelled
 * @param totalLatencyMillis aggregate wall-clock latency of those calls
 * @param capacityRefusals calls refused because all execution slots were occupied
 * @param timeouts calls that exceeded the configured execution-time budget
 * @param responseLimitRefusals responses replaced because they exceeded the configured byte budget
 * @param cancellations calls cancelled before they ended, counted apart from timeouts: request-scoped streams the
 *     client closed, and calls a cancellation reached while they ran
 * @param toolCount the number of tools currently advertised
 * @param tools the catalog of advertised tools
 */
public record McpServerStatus(
        boolean enabled,
        String configuredMode,
        boolean overridden,
        String serverName,
        String serverVersion,
        String transport,
        String endpoint,
        String protocolVersion,
        List<String> supportedProtocolVersions,
        int maxResults,
        long callCount,
        long totalLatencyMillis,
        long capacityRefusals,
        long timeouts,
        long responseLimitRefusals,
        long cancellations,
        int toolCount,
        List<McpToolInfo> tools) {

    public McpServerStatus {
        supportedProtocolVersions = DtoCollections.immutableCopy(supportedProtocolVersions);
        tools = DtoCollections.immutableCopy(tools);
    }

    public McpServerStatus(
            boolean enabled,
            String configuredMode,
            boolean overridden,
            String serverName,
            String serverVersion,
            String transport,
            String endpoint,
            String protocolVersion,
            int maxResults,
            int toolCount,
            List<McpToolInfo> tools) {
        this(
                enabled,
                configuredMode,
                overridden,
                serverName,
                serverVersion,
                transport,
                endpoint,
                protocolVersion,
                protocolVersion == null ? List.of() : List.of(protocolVersion),
                maxResults,
                0,
                0,
                0,
                0,
                0,
                0,
                toolCount,
                tools);
    }
}
