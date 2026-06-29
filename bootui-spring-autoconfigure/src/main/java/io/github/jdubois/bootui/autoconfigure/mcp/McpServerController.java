package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.McpServerStatus;
import io.github.jdubois.bootui.core.dto.McpToolInfo;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read/write panel endpoint for the MCP Server panel under {@code /bootui/api/mcp-server}.
 *
 * <p>{@code GET} returns the live status of the BootUI MCP server (enabled state, configured mode,
 * transport, endpoint, and the catalog of advertised tools with their backing panel toggles).
 * {@code POST /toggle} flips the live state via {@link McpServerState}, overriding the configured
 * {@code bootui.mcp.enabled} property for the lifetime of the running application.
 *
 * <p>The panel is registered as an action-capable BootUI panel, so the toggle is refused when the
 * {@code mcp-server} panel is disabled or read-only, exactly like every other state-changing panel
 * action.
 */
@RestController
@RequestMapping("/bootui/api/mcp-server")
public class McpServerController {

    private final McpServerState state;
    private final BootUiMcpTools tools;
    private final BootUiProperties properties;

    public McpServerController(McpServerState state, BootUiMcpTools tools, BootUiProperties properties) {
        this.state = state;
        this.tools = tools;
        this.properties = properties;
    }

    @GetMapping
    public McpServerStatus status() {
        return buildStatus();
    }

    @PostMapping("/toggle")
    public McpServerStatus toggle(@RequestBody(required = false) ToggleRequest request) {
        boolean target = request == null || request.enabled() == null ? !state.isEnabled() : request.enabled();
        state.setEnabled(target);
        return buildStatus();
    }

    private McpServerStatus buildStatus() {
        List<McpToolInfo> toolInfos = tools.tools().stream()
                .map(tool -> new McpToolInfo(
                        tool.name(),
                        tool.description(),
                        tool.panelId(),
                        tool.action(),
                        properties.isPanelEnabled(tool.panelId()),
                        properties.isPanelReadOnly(tool.panelId())))
                .toList();
        return new McpServerStatus(
                state.isEnabled(),
                state.configuredMode().name(),
                state.overridden(),
                BootUiMcpService.SERVER_NAME,
                serverVersion(),
                "http",
                "/bootui/api/mcp",
                BootUiMcpService.DEFAULT_PROTOCOL_VERSION,
                Math.max(1, properties.getMcp().getMaxResults()),
                toolInfos.size(),
                toolInfos);
    }

    private static String serverVersion() {
        String version = BootUiAutoConfiguration.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /** Request body for {@code POST /toggle}; a {@code null} {@code enabled} flips the current state. */
    public record ToggleRequest(Boolean enabled) {}
}
