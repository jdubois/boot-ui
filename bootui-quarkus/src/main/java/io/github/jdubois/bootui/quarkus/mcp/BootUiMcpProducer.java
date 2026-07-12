package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAccessConfig;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

/**
 * CDI producers for the BootUI MCP server on Quarkus.
 */
@ApplicationScoped
public class BootUiMcpProducer {

    private static final String INSTRUCTIONS =
            "BootUI exposes a running Quarkus application. Call the *_scan advisor tools to get "
                    + "actionable findings to fix, and the get_* tools (live activity, exceptions, security logs, "
                    + "SQL traces, traces, HTTP exchanges, config, beans, mappings) to understand runtime behavior. "
                    + "Use get_live_activity for a correlated feed of recent HTTP requests, SQL statements, "
                    + "exceptions, and security events (grouped by request/trace), and get_exception_detail "
                    + "(by id) for one exception's full stack trace, causes, and occurrences. All data is read "
                    + "locally and secret values are masked.";

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
        int maxConcurrentCalls = maxConcurrentCalls(config);
        McpPanelPolicy policy = new QuarkusMcpPanelPolicy(new QuarkusPanelAccessConfig(config));
        return new McpDispatcher(tools.tools(), policy, serverVersion(), INSTRUCTIONS, maxResults, maxConcurrentCalls);
    }

    public static int maxConcurrentCalls(Config config) {
        return Math.max(
                1,
                config.getOptionalValue("bootui.mcp.max-concurrent-calls", Integer.class)
                        .orElse(McpProtocol.DEFAULT_MAX_CONCURRENT_CALLS));
    }

    public static int maxPayloadBytes(Config config) {
        return Math.max(
                1,
                config.getOptionalValue("bootui.mcp.max-payload-bytes", Integer.class)
                        .orElse(McpProtocol.DEFAULT_MAX_PAYLOAD_BYTES));
    }

    private static String serverVersion() {
        return BootUiMcpProducer.class.getPackage().getImplementationVersion();
    }
}
