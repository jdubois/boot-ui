package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.McpServerStatus;
import io.github.jdubois.bootui.core.dto.McpToolInfo;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.quarkus.mcp.McpServerState;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.config.Config;

/**
 * Read/write panel endpoint for the MCP Server panel on Quarkus ({@code /bootui/api/mcp-server}) — the
 * Quarkus analogue of the Spring adapter's {@code McpServerController}.
 *
 * <p>{@code GET} returns the live status of the BootUI MCP server (enabled state, configured mode,
 * transport, endpoint, and the catalog of advertised tools). {@code POST /toggle} flips the live state
 * via {@link McpServerState}, overriding the configured {@code bootui.mcp.enabled} value for the
 * lifetime of the running application; the toggle is gated by the shared {@code LocalhostGuard} write
 * floor enforced by {@code BootUiQuarkusSafetyFilter}.
 *
 * <p>Quarkus has no per-panel enable/read-only model yet, so each tool's {@code panelEnabled} is
 * reported {@code true} and {@code panelReadOnly} {@code false} — the catalog itself is already gated
 * by panel availability when it is built ({@code QuarkusMcpTools}).
 */
@Path("/bootui/api/mcp-server")
public class McpServerResource {

    private final McpServerState state;
    private final McpDispatcher dispatcher;
    private final int maxResults;

    @Inject
    public McpServerResource(McpServerState state, McpDispatcher dispatcher, Config config) {
        this.state = state;
        this.dispatcher = dispatcher;
        this.maxResults = Math.max(
                1,
                config.getOptionalValue("bootui.mcp.max-results", Integer.class).orElse(200));
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public McpServerStatus status() {
        return buildStatus();
    }

    @POST
    @Path("/toggle")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public McpServerStatus toggle(ToggleRequest request) {
        boolean target = request == null || request.enabled() == null ? !state.isEnabled() : request.enabled();
        state.setEnabled(target);
        return buildStatus();
    }

    private McpServerStatus buildStatus() {
        List<McpToolInfo> toolInfos =
                dispatcher.tools().stream().map(McpServerResource::toInfo).toList();
        return new McpServerStatus(
                state.isEnabled(),
                state.configuredMode(),
                state.overridden(),
                McpProtocol.SERVER_NAME,
                serverVersion(),
                "http",
                "/bootui/api/mcp",
                McpProtocol.DEFAULT_PROTOCOL_VERSION,
                maxResults,
                toolInfos.size(),
                toolInfos);
    }

    private static McpToolInfo toInfo(McpTool tool) {
        // Quarkus has no per-panel enable/read-only toggle: panelEnabled=true, panelReadOnly=false.
        return new McpToolInfo(tool.name(), tool.description(), tool.panelId(), tool.action(), true, false);
    }

    private static String serverVersion() {
        String version = McpServerResource.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /** Request body for {@code POST /toggle}; a {@code null} {@code enabled} flips the current state. */
    public record ToggleRequest(Boolean enabled) {}
}
