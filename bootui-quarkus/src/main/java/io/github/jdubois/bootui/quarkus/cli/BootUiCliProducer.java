package io.github.jdubois.bootui.quarkus.cli;

import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.quarkus.QuarkusApplicationInfo;
import io.github.jdubois.bootui.quarkus.QuarkusBootUiPaths;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAccessConfig;
import io.github.jdubois.bootui.quarkus.QuarkusServerPortSupplier;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpFailureReporter;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpPanelPolicy;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpTools;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.microprofile.config.Config;

@ApplicationScoped
public class BootUiCliProducer {

    private final Config config;
    private final QuarkusServerPortSupplier portSupplier;
    private final QuarkusApplicationInfo applicationInfo;
    private volatile Path leaseFile;

    public BootUiCliProducer() {
        this(null, null, null);
    }

    @Inject
    public BootUiCliProducer(
            Config config, QuarkusServerPortSupplier portSupplier, QuarkusApplicationInfo applicationInfo) {
        this.config = config;
        this.portSupplier = portSupplier;
        this.applicationInfo = applicationInfo;
    }

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

    void onStart(@Observes StartupEvent event) {
        registerLeaseIfEligible(LaunchMode.current());
    }

    synchronized Path registerLeaseIfEligible(LaunchMode mode) {
        if (config == null || portSupplier == null || applicationInfo == null) {
            return null;
        }
        if (!enabled(config)
                || !config.getOptionalValue("bootui.cli.instance-discovery.enabled", Boolean.class)
                        .orElse(mode == LaunchMode.DEVELOPMENT)) {
            return null;
        }
        int port = portSupplier.localServerPort();
        if (port <= 0 || port > 65535) {
            return null;
        }
        String baseUrl = "http://localhost:" + port;
        String apiPath = QuarkusBootUiPaths.applicationApiPath(config);
        String appName = config.getOptionalValue("quarkus.application.name", String.class)
                .filter(name -> !name.isBlank())
                .orElse("application");
        String configuredDir = config.getOptionalValue("bootui.cli.instances-dir", String.class)
                .filter(dir -> !dir.isBlank())
                .or(() -> config.getOptionalValue("bootui.cli.instance-discovery.instances-dir", String.class)
                        .filter(dir -> !dir.isBlank()))
                .orElse(null);
        CliService.deleteInstanceLease(this.leaseFile);
        this.leaseFile = CliService.writeInstanceLease(
                applicationInfo.run(), appName, "Quarkus", baseUrl, apiPath, configuredDir);
        return this.leaseFile;
    }

    void onStop(@Observes ShutdownEvent event) {
        closeLease();
    }

    @PreDestroy
    synchronized void closeLease() {
        Path current = this.leaseFile;
        this.leaseFile = null;
        CliService.deleteInstanceLease(current);
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
