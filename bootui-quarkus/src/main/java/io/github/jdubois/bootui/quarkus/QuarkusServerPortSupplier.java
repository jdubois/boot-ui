package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.spi.ServerPortSupplier;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus implementation of the framework-neutral {@link ServerPortSupplier} consumed by the engine
 * {@code HttpProbeService} behind the HTTP Probe panel.
 *
 * <p>This is the Quarkus analogue of the Spring adapter's inline {@code local.server.port} lambda. The
 * engine always probes {@code http://localhost:<port><path>}, so the only per-framework detail is
 * <em>which</em> port the application is actually listening on right now. Spring exposes that bound port
 * as {@code local.server.port}; Quarkus has no single equivalent config key, because its HTTP port
 * depends on the launch mode:</p>
 *
 * <ul>
 *   <li><strong>{@link LaunchMode#TEST}</strong> &rarr; {@code quarkus.http.test-port} (default
 *       {@code 8081}). Under {@code @QuarkusTest} the server binds to the <em>test</em> port, not
 *       {@code quarkus.http.port}, so a probe must target the test port to reach the running app.</li>
 *   <li><strong>{@link LaunchMode#DEVELOPMENT} / {@link LaunchMode#NORMAL}</strong> &rarr;
 *       {@code quarkus.http.port} (default {@code 8080}). The console is only wired in non-production
 *       modes, so in practice this is the dev-mode port.</li>
 * </ul>
 *
 * <p>Selecting the key by launch mode is the same idea as Spring reading the <em>bound</em> port rather
 * than the configured one: it keeps the probe pointed at the port the app is genuinely serving on. The
 * value is read <em>live</em> on every probe (the bound port is only known once the server is running)
 * and {@link #localServerPort()} fails soft to the Quarkus default for the active mode when nothing is
 * configured.</p>
 *
 * <p>This even resolves a <em>random</em> port ({@code quarkus.http.port=0} /
 * {@code quarkus.http.test-port=0}): after binding a random port Quarkus rewrites the corresponding
 * {@code quarkus.http.*-port} system property to the actual bound port, and because the supplier reads
 * Config live (system properties being a higher-ordinal config source) a probe issued once the server is
 * up picks up that resolved port. {@code BootUiQuarkusHttpProbeRandomPortTest} pins this end-to-end, so a
 * future Quarkus that stopped rewriting the property would fail the build rather than silently break the
 * probe.</p>
 */
@ApplicationScoped
public class QuarkusServerPortSupplier implements ServerPortSupplier {

    static final String HTTP_PORT_KEY = "quarkus.http.port";
    static final String HTTP_TEST_PORT_KEY = "quarkus.http.test-port";
    static final int DEFAULT_HTTP_PORT = 8080;
    static final int DEFAULT_HTTP_TEST_PORT = 8081;

    private final Config config;

    @Inject
    public QuarkusServerPortSupplier(Config config) {
        this.config = config;
    }

    @Override
    public int localServerPort() {
        LaunchMode mode = LaunchMode.current();
        return config.getOptionalValue(portKey(mode), Integer.class).orElse(defaultPort(mode));
    }

    static String portKey(LaunchMode mode) {
        return mode == LaunchMode.TEST ? HTTP_TEST_PORT_KEY : HTTP_PORT_KEY;
    }

    static int defaultPort(LaunchMode mode) {
        return mode == LaunchMode.TEST ? DEFAULT_HTTP_TEST_PORT : DEFAULT_HTTP_PORT;
    }
}
