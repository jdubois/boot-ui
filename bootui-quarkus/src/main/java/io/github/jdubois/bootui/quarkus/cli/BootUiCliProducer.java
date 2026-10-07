package io.github.jdubois.bootui.quarkus.cli;

import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.quarkus.QuarkusBootUiPaths;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAccessConfig;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpFailureReporter;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpPanelPolicy;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpTools;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;
import org.eclipse.microprofile.config.Config;

/**
 * CDI producer for the command-line facade at {@code /bootui/api/cli}.
 *
 * <p>Builds a {@link CliService} over the same {@link QuarkusMcpTools} registry the MCP server uses, with its
 * own dispatcher so command-line traffic is not counted as MCP agent activity.
 */
@ApplicationScoped
public class BootUiCliProducer {

    @Produces
    @Singleton
    public CliService cliService(QuarkusMcpTools tools, Config config, QuarkusMcpFailureReporter failureReporter) {
        McpPanelPolicy policy = new QuarkusMcpPanelPolicy(new QuarkusPanelAccessConfig(config));
        return new CliService(
                enabled(config),
                tools::tools,
                policy,
                serverVersion(),
                QuarkusBootUiPaths.applicationPath(config, QuarkusBootUiPaths.apiPath(config)) + "/cli",
                maxResults(config),
                maxConcurrentCalls(config),
                executionTimeoutMillis(config),
                failureReporter,
                tools::panelUnavailableReason);
    }

    private static boolean enabled(Config config) {
        return config.getOptionalValue("bootui.cli.enabled", Boolean.class).orElse(true);
    }

    private static int maxResults(Config config) {
        return Math.max(
                1,
                config.getOptionalValue("bootui.cli.max-results", Integer.class).orElse(200));
    }

    private static int maxConcurrentCalls(Config config) {
        return Math.max(
                1,
                config.getOptionalValue("bootui.cli.max-concurrent-calls", Integer.class)
                        .orElse(McpProtocol.DEFAULT_MAX_CONCURRENT_CALLS));
    }

    private static long executionTimeoutMillis(Config config) {
        return Math.max(
                1,
                config.getOptionalValue("bootui.cli.execution-timeout", Duration.class)
                        .orElse(Duration.ofMillis(McpProtocol.DEFAULT_EXECUTION_TIMEOUT_MILLIS))
                        .toMillis());
    }

    private static String serverVersion() {
        return BootUiCliProducer.class.getPackage().getImplementationVersion();
    }
}
