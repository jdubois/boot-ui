package io.github.jdubois.bootui.quarkus.cli;

import io.github.jdubois.bootui.engine.cli.InstanceLease;
import io.github.jdubois.bootui.engine.cli.InstanceLeaseManager;
import io.github.jdubois.bootui.quarkus.QuarkusApplicationInfo;
import io.github.jdubois.bootui.quarkus.QuarkusBootUiPaths;
import io.github.jdubois.bootui.quarkus.QuarkusServerPortSupplier;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import org.eclipse.microprofile.config.Config;

/**
 * Writes and cleans up the local BootUI instance lease file ({@code ~/.bootui/instances/<instanceId>.json})
 * on Quarkus after the HTTP port is bound.
 *
 * <p>Enabled by default in {@link LaunchMode#DEVELOPMENT} and disabled by default in {@link LaunchMode#TEST}
 * unless {@code bootui.cli.instance-discovery.enabled=true} is explicitly configured. Never registered in
 * {@link LaunchMode#NORMAL}.
 */
@Singleton
public class QuarkusInstanceLeaseLifecycle {

    private final Config config;
    private final QuarkusServerPortSupplier portSupplier;
    private final QuarkusApplicationInfo applicationInfo;
    private volatile InstanceLeaseManager manager;

    @Inject
    public QuarkusInstanceLeaseLifecycle(
            Config config, QuarkusServerPortSupplier portSupplier, QuarkusApplicationInfo applicationInfo) {
        this.config = config;
        this.portSupplier = portSupplier;
        this.applicationInfo = applicationInfo;
    }

    void onStart(@Observes StartupEvent event) {
        registerIfEligible(LaunchMode.current());
    }

    public synchronized Path registerIfEligible(LaunchMode mode) {
        if (!config.getOptionalValue("bootui.cli.enabled", Boolean.class).orElse(true)) {
            return null;
        }
        if (!isDiscoveryEnabled(config, mode)) {
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
        Path instancesDir = InstanceLeaseManager.resolveInstancesDir(configuredDir);
        if (instancesDir == null) {
            return null;
        }
        InstanceLease lease;
        try {
            lease = InstanceLease.forCurrentProcess(applicationInfo.run(), appName, "Quarkus", baseUrl, apiPath);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        InstanceLeaseManager current = this.manager;
        if (current == null || !instancesDir.equals(current.instancesDir())) {
            if (current != null) {
                current.close();
            }
            current = new InstanceLeaseManager(instancesDir);
            this.manager = current;
        }
        return current.writeQuietly(lease);
    }

    static boolean isDiscoveryEnabled(Config config, LaunchMode mode) {
        return config.getOptionalValue("bootui.cli.instance-discovery.enabled", Boolean.class)
                .orElse(mode == LaunchMode.DEVELOPMENT);
    }

    /** Currently active lease file, if any (exposed for testing). */
    public Path leaseFile() {
        InstanceLeaseManager current = this.manager;
        return current == null ? null : current.leaseFile();
    }

    void onStop(@Observes ShutdownEvent event) {
        close();
    }

    @PreDestroy
    public synchronized void close() {
        InstanceLeaseManager current = this.manager;
        this.manager = null;
        if (current != null) {
            current.close();
        }
    }
}
