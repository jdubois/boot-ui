package io.github.jdubois.bootui.autoconfigure.cli;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.javaagent.BootUiAgentClaimEnvironmentPostProcessor;
import io.github.jdubois.bootui.engine.cli.InstanceLease;
import io.github.jdubois.bootui.engine.cli.InstanceLeaseManager;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;

/**
 * Writes and cleans up the local BootUI instance lease file ({@code ~/.bootui/instances/<instanceId>.json})
 * for Spring MVC and Spring WebFlux applications.
 *
 * <p>The lease is written only after the web server port is bound and known ({@code WebServerInitializedEvent}
 * or {@code ApplicationReadyEvent}), never during bean construction when {@code server.port=0} is still
 * unresolved. Disabled by default in tests unless {@code bootui.cli.instance-discovery.enabled=true} is set,
 * and disabled whenever {@code bootui.cli.enabled=false}.
 */
public final class BootUiInstanceLeaseLifecycle implements ApplicationListener<ApplicationEvent>, DisposableBean {

    private final RunIdentity run;
    private final BootUiProperties properties;
    private final ApplicationContext context;
    private final boolean startedInTest;
    private volatile InstanceLeaseManager manager;

    public BootUiInstanceLeaseLifecycle(RunIdentity run, BootUiProperties properties, ApplicationContext context) {
        this(run, properties, context, BootUiAgentClaimEnvironmentPostProcessor.startedByTestFramework());
    }

    BootUiInstanceLeaseLifecycle(
            RunIdentity run, BootUiProperties properties, ApplicationContext context, boolean startedInTest) {
        this.run = Objects.requireNonNull(run, "run");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.context = Objects.requireNonNull(context, "context");
        this.startedInTest = startedInTest;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof WebServerInitializedEvent webEvent) {
            if (webEvent.getApplicationContext() == context && webEvent.getWebServer() != null) {
                int port = webEvent.getWebServer().getPort();
                if (port > 0) {
                    registerIfEligible(port);
                }
            }
        } else if (event instanceof ApplicationReadyEvent readyEvent) {
            if (readyEvent.getApplicationContext() == context && manager == null) {
                registerIfEligible(null);
            }
        } else if (event instanceof ContextClosedEvent closedEvent) {
            if (closedEvent.getApplicationContext() == context) {
                closeManager();
            }
        }
    }

    synchronized Path registerIfEligible(Integer boundPort) {
        if (!properties.getCli().isEnabled()) {
            return null;
        }
        Environment environment = context.getEnvironment();
        if (!isDiscoveryEnabled(environment)) {
            return null;
        }
        int port = resolvePort(environment, boundPort);
        if (port <= 0 || port > 65535) {
            return null;
        }
        boolean sslEnabled = isSslEnabled(environment);
        String baseUrl = (sslEnabled ? "https" : "http") + "://localhost:" + port;
        String apiPath = resolveEffectiveApiPath(environment);
        String appName = environment.getProperty("spring.application.name", "application");
        Path instancesDir = resolveInstancesDir(environment);
        if (instancesDir == null) {
            return null;
        }
        InstanceLease lease;
        try {
            lease = InstanceLease.forCurrentProcess(run, appName, "Spring Boot", baseUrl, apiPath);
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

    boolean isDiscoveryEnabled(Environment environment) {
        Boolean configured = properties.getCli().getInstanceDiscovery().getEnabled();
        if (configured == null && environment != null) {
            String raw = environment.getProperty("bootui.cli.instance-discovery.enabled");
            if (raw != null && !raw.isBlank()) {
                configured = Boolean.parseBoolean(raw.trim());
            }
        }
        if (configured != null) {
            return configured;
        }
        return !startedInTest && !BootUiAgentClaimEnvironmentPostProcessor.startedByTestFramework();
    }

    String resolveEffectiveApiPath(Environment environment) {
        if (context instanceof ReactiveWebApplicationContext) {
            String basePath = environment == null ? "" : environment.getProperty("spring.webflux.base-path", "");
            return InstanceLease.composeApiPath(properties.getApiPath(), basePath);
        }
        String contextPath = environment == null ? "" : environment.getProperty("server.servlet.context-path", "");
        String servletPath = environment == null ? "" : environment.getProperty("spring.mvc.servlet.path", "");
        return InstanceLease.composeApiPath(properties.getApiPath(), contextPath, servletPath);
    }

    private Path resolveInstancesDir(Environment environment) {
        String configured = properties.getCli().resolveInstancesDir();
        if ((configured == null || configured.isBlank()) && environment != null) {
            configured = environment.getProperty(
                    "bootui.cli.instances-dir", environment.getProperty("bootui.cli.instance-discovery.instances-dir"));
        }
        return InstanceLeaseManager.resolveInstancesDir(configured);
    }

    private static boolean isSslEnabled(Environment environment) {
        if (environment == null) {
            return false;
        }
        Boolean explicit = environment.getProperty("server.ssl.enabled", Boolean.class);
        if (explicit != null) {
            return explicit;
        }
        String bundle = environment.getProperty("server.ssl.bundle");
        String keyStore = environment.getProperty("server.ssl.key-store");
        return (bundle != null && !bundle.isBlank()) || (keyStore != null && !keyStore.isBlank());
    }

    private static int resolvePort(Environment environment, Integer boundPort) {
        if (boundPort != null && boundPort > 0) {
            return boundPort;
        }
        if (environment == null) {
            return -1;
        }
        String raw = environment.getProperty("local.server.port");
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    /** Currently active lease file, if any (exposed for testing). */
    public Path leaseFile() {
        InstanceLeaseManager current = this.manager;
        return current == null ? null : current.leaseFile();
    }

    @Override
    public void destroy() {
        closeManager();
    }

    private synchronized void closeManager() {
        InstanceLeaseManager current = this.manager;
        this.manager = null;
        if (current != null) {
            current.close();
        }
    }
}
