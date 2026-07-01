package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

/**
 * CDI producers for the BootUI MCP server on Quarkus.
 *
 * <p>Mirrors the Spring adapter's {@code McpConfiguration} {@code @Bean} methods: it builds the live
 * {@link McpServerState} from {@code bootui.mcp.enabled} and the framework- and JSON-free engine
 * {@link McpDispatcher} from the Quarkus tool catalog, the always-enabled {@link QuarkusMcpPanelPolicy}
 * (Quarkus has no per-panel toggle yet), the BootUI version, Quarkus-flavored {@code initialize}
 * instructions, and the {@code bootui.mcp.max-results} cap.
 *
 * <p>{@link McpServerState} is annotation-free and produced here as a {@code @Singleton} rather than
 * being CDI-scoped itself, to avoid the ambiguity of a class being both {@code @ApplicationScoped} and
 * {@code @Produces}d.
 */
@ApplicationScoped
public class BootUiMcpProducer {

    private static final String INSTRUCTIONS =
            "BootUI exposes a running Quarkus application. Call the *_scan advisor tools to get "
                    + "actionable findings to fix, and the get_* tools (exceptions, security logs, SQL traces, "
                    + "traces, HTTP exchanges, config, beans, mappings) to understand runtime behavior. All data "
                    + "is read locally and secret values are masked.";

    @Produces
    @Singleton
    public McpServerState mcpServerState(Config config) {
        String mode =
                config.getOptionalValue("bootui.mcp.enabled", String.class).orElse("OFF");
        return new McpServerState(mode);
    }

    @Produces
    @Singleton
    public McpDispatcher mcpDispatcher(QuarkusMcpTools tools, Config config) {
        int maxResults =
                config.getOptionalValue("bootui.mcp.max-results", Integer.class).orElse(200);
        return new McpDispatcher(tools.tools(), new QuarkusMcpPanelPolicy(), serverVersion(), INSTRUCTIONS, maxResults);
    }

    private static String serverVersion() {
        // null under the QuarkusClassLoader; the dispatcher coalesces null -> "dev".
        return BootUiMcpProducer.class.getPackage().getImplementationVersion();
    }
}
