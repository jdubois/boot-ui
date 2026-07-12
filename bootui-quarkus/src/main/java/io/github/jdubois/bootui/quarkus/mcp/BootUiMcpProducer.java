package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpGuidance;
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

    private static final String FRAMEWORK = "Quarkus";
    private static final String INSTRUCTIONS = McpGuidance.instructions(FRAMEWORK);

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
        return new McpDispatcher(
                tools.tools(),
                McpGuidance.prompts(FRAMEWORK),
                policy,
                serverVersion(),
                INSTRUCTIONS,
                maxResults,
                maxConcurrentCalls);
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
