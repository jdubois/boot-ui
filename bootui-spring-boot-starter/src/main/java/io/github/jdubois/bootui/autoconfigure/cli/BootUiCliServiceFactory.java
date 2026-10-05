package io.github.jdubois.bootui.autoconfigure.cli;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.mcp.McpFailureReporter;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@link CliService} backing {@code /bootui/api/cli} from Spring configuration.
 *
 * <p>Shared by the servlet and reactive stacks: both hand it the same thing — a supplier over their own MCP
 * tool registry — so the command-line surface cannot diverge between them.
 */
public final class BootUiCliServiceFactory {

    private static final Logger log = LoggerFactory.getLogger(BootUiCliServiceFactory.class);

    private BootUiCliServiceFactory() {}

    /** Builds the facade over {@code tools}, applying the {@code bootui.cli.*} settings. */
    public static CliService create(
            Supplier<List<McpTool>> tools, McpPanelPolicy policy, BootUiProperties properties, String serverVersion) {
        BootUiProperties.Cli cli = properties.getCli();
        return new CliService(
                cli.isEnabled(),
                tools,
                policy,
                serverVersion,
                properties.getApiPath() + "/cli",
                Math.max(1, cli.getMaxResults()),
                Math.max(1, cli.getMaxConcurrentCalls()),
                Math.max(1, cli.getExecutionTimeout().toMillis()),
                reporter());
    }

    private static McpFailureReporter reporter() {
        return (operation, failure) -> log.error("BootUI CLI failure while {}", operation, failure);
    }
}
