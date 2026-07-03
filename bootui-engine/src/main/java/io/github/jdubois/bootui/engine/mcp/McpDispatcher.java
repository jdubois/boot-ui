package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.InitializeResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.NoResponse;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PingResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolsListResult;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.util.List;

/**
 * Framework- and JSON-free core of the BootUI MCP server: it routes an already-parsed
 * {@link McpRequest} to a typed {@link McpDispatchOutcome}, applying the same method routing,
 * notification handling, per-panel gating, tool lookup and {@code max-results} capping the browser UI
 * obeys.
 *
 * <p>Each adapter keeps a thin envelope codec that parses a request node into an {@link McpRequest},
 * calls {@link #dispatch(McpRequest)}, and renders the outcome back to JSON with its own
 * {@code ObjectMapper} (Jackson 3 on Spring Boot, Jackson 2 on Quarkus). The control flow here is a
 * one-to-one translation of the original Spring {@code BootUiMcpService} so both adapters answer
 * byte-identically: a refused gate / missing / unknown tool is an in-band {@link ToolCallError}
 * ({@code isError:true}); a tool handler throwing a {@link RuntimeException} becomes a JSON-RPC
 * {@link ProtocolError} ({@code -32603}); serialization of a successful payload (the only remaining
 * Jackson step) is performed and error-handled by the adapter codec.
 */
public final class McpDispatcher {

    private final List<McpTool> tools;
    private final McpPanelPolicy policy;
    private final String serverVersion;
    private final String instructions;
    private final int maxResults;

    /**
     * @param tools the advertised tool catalog, in order (each adapter wires its own controllers /
     *     resources)
     * @param policy the per-panel enable / read-only gate behind {@code tools/call}
     * @param serverVersion the server version advertised in {@code initialize} ({@code null} → {@code "dev"})
     * @param instructions the framework-specific usage instructions advertised in {@code initialize}
     * @param maxResults the {@code bootui.mcp.max-results} cap applied to paged read tools (floored at 1)
     */
    public McpDispatcher(
            List<McpTool> tools, McpPanelPolicy policy, String serverVersion, String instructions, int maxResults) {
        this.tools = List.copyOf(tools);
        this.policy = policy;
        this.serverVersion = serverVersion == null ? "dev" : serverVersion;
        this.instructions = instructions;
        this.maxResults = Math.max(1, maxResults);
    }

    /** The advertised tool catalog, in order. */
    public List<McpTool> tools() {
        return tools;
    }

    /**
     * Routes a single parsed JSON-RPC request to a typed outcome. Returns {@link NoResponse} for a
     * notification with no applicable response; the adapter then emits no body (HTTP 202).
     */
    public McpDispatchOutcome dispatch(McpRequest request) {
        String method = request.method();
        if (method == null || method.isEmpty()) {
            return request.notification()
                    ? new NoResponse()
                    : new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_METHOD_MESSAGE);
        }
        return switch (method) {
            case "initialize" -> initialize(request);
            case "ping" -> new PingResult();
            case "tools/list" ->
                new ToolsListResult(tools.stream().map(McpTool::describe).toList());
            case "tools/call" -> callTool(request);
            default ->
                request.notification()
                        ? new NoResponse()
                        : new ProtocolError(McpProtocol.METHOD_NOT_FOUND, "Unknown method: " + method);
        };
    }

    private McpDispatchOutcome initialize(McpRequest request) {
        String requested = request.requestedProtocolVersion();
        String protocolVersion =
                (requested == null || requested.isEmpty()) ? McpProtocol.DEFAULT_PROTOCOL_VERSION : requested;
        return new InitializeResult(protocolVersion, McpProtocol.SERVER_NAME, serverVersion, instructions);
    }

    private McpDispatchOutcome callTool(McpRequest request) {
        String name = request.toolName();
        if (name == null || name.isEmpty()) {
            return new ToolCallError(McpProtocol.MISSING_TOOL_NAME_MESSAGE);
        }
        McpTool tool = findTool(name);
        if (tool == null) {
            return new ToolCallError("Unknown tool: " + name);
        }
        if (!policy.isEnabled(tool.panelId())) {
            return new ToolCallError(policy.disabledReason(tool.panelId()));
        }
        if (tool.action() && policy.isReadOnly(tool.panelId())) {
            return new ToolCallError(policy.readOnlyReason(tool.panelId()));
        }
        McpArguments arguments =
                McpArguments.normalize(request.rawQuery(), request.rawLimit(), request.rawId(), maxResults);
        if (tool.schema() == McpToolSchema.ID && arguments.id() == null) {
            return new ToolCallError(McpProtocol.MISSING_ID_ARGUMENT_MESSAGE);
        }
        try {
            Object payload = tool.invoke(arguments);
            return new ToolCallResult(payload);
        } catch (RuntimeException ex) {
            // A tool handler failure becomes a JSON-RPC error (-32603), exactly as the original Spring
            // service reported a generic RuntimeException out of the tools/call switch.
            return new ProtocolError(McpProtocol.INTERNAL_ERROR, ex.getMessage());
        }
    }

    private McpTool findTool(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
