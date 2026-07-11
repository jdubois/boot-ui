package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * Logs {@code "BootUI is available at <url>"} once at application startup, mirroring the Spring adapter's
 * {@code BootUiAutoConfiguration.bootUiStartupBanner}. The bean is only registered in {@code dev}/{@code test}
 * launch modes (see {@code BootUiQuarkusProcessor.registerConsole}, which is skipped entirely in
 * {@code LaunchMode.NORMAL}), so the banner never prints in a production build where the console is dark.
 *
 * <p>The message text is kept identical to Spring so both adapters log the same line. Toggle it with
 * {@code bootui.show-banner} (default {@code true}), the same key the Spring adapter honors.
 *
 * <p><strong>URL assumptions.</strong> The console is a local developer tool, so the banner always builds an
 * {@code http://localhost:<port><root-path>/bootui} URL: the scheme is plain {@code http} (Quarkus has no single
 * SSL-enabled flag analogous to Spring's {@code server.ssl.enabled}, and TLS-only dev is rare), the port is the
 * live bound HTTP port resolved by {@link QuarkusServerPortSupplier} (launch-mode aware), and the path includes
 * {@code quarkus.http.root-path} plus the fixed {@code /bootui} mount (unlike Spring, {@code bootui.path} does not
 * relocate the Quarkus UI). Quarkus also logs its own authoritative {@code "Listening on:"} line, so this banner is
 * a best-effort convenience. With a randomly assigned port ({@code quarkus.http.port=0}) the value is read after the
 * bind, so it is normally already resolved by {@link StartupEvent}.
 */
@ApplicationScoped
public class BootUiQuarkusStartupBanner {

    static final String SHOW_BANNER_KEY = "bootui.show-banner";
    static final String ROOT_PATH_KEY = "quarkus.http.root-path";
    static final String BASE_PATH = "/bootui";

    private static final Logger LOG = Logger.getLogger(BootUiQuarkusStartupBanner.class);

    private final Config config;
    private final QuarkusServerPortSupplier portSupplier;
    private final ApiTokenAuthenticator authenticator;

    @Inject
    public BootUiQuarkusStartupBanner(
            Config config, QuarkusServerPortSupplier portSupplier, ApiTokenAuthenticator authenticator) {
        this.config = config;
        this.portSupplier = portSupplier;
        this.authenticator = authenticator;
    }

    void onStart(@Observes StartupEvent event) {
        if (showBanner(config)) {
            LOG.infof("BootUI is available at %s", buildStartupUrl(portSupplier.localServerPort(), rootPath()));
        }
        if (authenticator.generated() && remoteAccessConfigured()) {
            LOG.infof("BootUI bearer token for non-local API access: %s", authenticator.token());
        }
    }

    private boolean remoteAccessConfigured() {
        return config.getOptionalValue("bootui.allow-non-localhost", Boolean.class)
                        .orElse(false)
                || config.getOptionalValue("bootui.trusted-proxies", String.class)
                        .filter(value -> !value.isBlank())
                        .isPresent()
                || !"OFF"
                        .equalsIgnoreCase(config.getOptionalValue("bootui.trust-container-gateway", String.class)
                                .orElse("OFF"));
    }

    private String rootPath() {
        return config.getOptionalValue(ROOT_PATH_KEY, String.class).orElse("/");
    }

    /**
     * Reads {@code bootui.show-banner}, defaulting to {@code true} (Spring parity). SmallRye's boolean converter
     * never throws on an unrecognized value (anything outside the truthy set is {@code false}), so a malformed value
     * simply suppresses the banner rather than failing startup.
     */
    static boolean showBanner(Config config) {
        return config.getOptionalValue(SHOW_BANNER_KEY, Boolean.class).orElse(Boolean.TRUE);
    }

    /**
     * Builds {@code http://localhost:<port><normalized-root>/bootui}. The root path is normalized so a default
     * {@code "/"} (or blank) contributes nothing and any custom root is rendered without a trailing slash — e.g.
     * {@code "/"} → {@code http://localhost:8080/bootui}, {@code "/app"} or {@code "/app/"} →
     * {@code http://localhost:8080/app/bootui}.
     */
    static String buildStartupUrl(int port, String rootPath) {
        return "http://localhost:" + port + normalizeRootPath(rootPath) + BASE_PATH;
    }

    private static String normalizeRootPath(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            return "";
        }
        String trimmed = rootPath.strip();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        int end = trimmed.length();
        while (end > 1 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        trimmed = trimmed.substring(0, end);
        return trimmed.equals("/") ? "" : trimmed;
    }
}
