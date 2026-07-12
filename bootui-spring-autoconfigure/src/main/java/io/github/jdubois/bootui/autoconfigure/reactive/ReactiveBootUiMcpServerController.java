package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.mcp.McpServerState;
import io.github.jdubois.bootui.core.dto.McpServerStatus;
import io.github.jdubois.bootui.core.dto.McpToolInfo;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Reactive WebFlux twin of {@code McpServerController}. */
@RestController
@RequestMapping("/bootui/api/mcp-server")
public class ReactiveBootUiMcpServerController {

    private final McpServerState state;
    private final ReactiveBootUiMcpTools tools;
    private final BootUiProperties properties;

    public ReactiveBootUiMcpServerController(
            McpServerState state, ReactiveBootUiMcpTools tools, BootUiProperties properties) {
        this.state = state;
        this.tools = tools;
        this.properties = properties;
    }

    @GetMapping
    public Mono<McpServerStatus> status() {
        return Mono.just(buildStatus());
    }

    @PostMapping("/toggle")
    public Mono<McpServerStatus> toggle(@RequestBody(required = false) ToggleRequest request) {
        boolean target = request == null || request.enabled() == null ? !state.isEnabled() : request.enabled();
        state.setEnabled(target);
        return Mono.just(buildStatus());
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
                McpProtocol.SERVER_NAME,
                serverVersion(),
                "http",
                "/bootui/api/mcp",
                McpProtocol.DEFAULT_PROTOCOL_VERSION,
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
