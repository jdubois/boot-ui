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
 * @param protocolVersion the MCP protocol revision advertised by default
 * @param maxResults the {@code bootui.mcp.max-results} cap applied to paginated read tools
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
        int maxResults,
        int toolCount,
        List<McpToolInfo> tools) {}
