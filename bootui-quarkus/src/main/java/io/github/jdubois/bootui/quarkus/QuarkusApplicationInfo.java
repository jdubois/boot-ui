package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.BootUiInfo;
import io.github.jdubois.bootui.core.dto.ActivationStatus;
import io.github.jdubois.bootui.core.dto.OverviewDto;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * Assembles the framework-neutral {@link OverviewDto} for the Quarkus adapter — the analogue of the
 * Spring adapter's {@code OverviewController}.
 *
 * <p>{@code GET /bootui/api/overview} is the shared shell's <em>chrome</em> data source (it powers the
 * header subtitle/status and primes the CSRF cookie). The Overview dashboard panel is available on
 * Quarkus, but its scoring dashboard is rendered entirely client-side from each advisor's own
 * endpoints (see {@code QuarkusPanelAvailability}), so this endpoint only needs to populate the
 * shell-chrome fields ({@code applicationName}, {@code frameworkName}, {@code frameworkVersion},
 * {@code javaVersion}, {@code activeProfiles}, {@code activation}); the remaining panel-only fields
 * are filled best-effort from {@link Config} and may be {@code null}.</p>
 *
 * <p>The Quarkus version is resolved from {@code bootui.internal.quarkus-version}, a config default the
 * deployment processor captures at build time via {@code io.quarkus.builder.Version}. Reading it at
 * runtime via {@code Package#getImplementationVersion()} returns {@code null} under the Quarkus
 * classloader in dev/test, so the build-time capture is the reliable source.</p>
 */
@ApplicationScoped
public class QuarkusApplicationInfo {

    private static final Logger LOG = Logger.getLogger(QuarkusApplicationInfo.class);

    public static final String QUARKUS_VERSION_KEY = "bootui.internal.quarkus-version";

    private final Config config;

    @Inject
    public QuarkusApplicationInfo(Config config) {
        this.config = config;
    }

    public OverviewDto overview() {
        return new OverviewDto(
                BootUiInfo.VERSION,
                applicationName(),
                "Quarkus",
                optString(QUARKUS_VERSION_KEY, null),
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                activeProfiles(),
                List.of(),
                // Quarkus REST runs on Vert.x; it has no Spring servlet/reactive web-type distinction,
                // and the dashboard does not surface this field, so leave it unset.
                null,
                optInt("quarkus.http.port"),
                optInt("quarkus.management.port"),
                optString("quarkus.http.root-path", ""),
                null,
                activation(),
                null);
    }

    private String applicationName() {
        String name = optString("quarkus.application.name", null);
        return (name == null || name.isBlank()) ? "application" : name;
    }

    private List<String> activeProfiles() {
        try {
            return List.copyOf(config.unwrap(SmallRyeConfig.class).getProfiles());
        } catch (RuntimeException ex) {
            LOG.debugf(ex, "Unable to resolve active Quarkus profiles; reporting none.");
            return List.of();
        }
    }

    private ActivationStatus activation() {
        // The console is only wired outside production launch mode (the deployment processor gates
        // registration on LaunchMode != NORMAL), so whenever this endpoint can answer, BootUI is active.
        // localhostOnly mirrors the Spring adapter's OverviewController: BootUiQuarkusSafetyFilter enforces
        // the full shared LocalhostGuard policy (loopback-source trust, Host allow-list, and cross-site-write
        // rejection) over the whole /bootui surface, bypassed only when bootui.allow-non-localhost=true.
        boolean localhostOnly = !optBoolean(BootUiQuarkusSafetyFilter.ALLOW_NON_LOCALHOST_KEY, false);
        return new ActivationStatus(
                true,
                localhostOnly,
                "BootUI is active on Quarkus (the console is wired only outside production launch mode).",
                List.of());
    }

    private String optString(String key, String fallback) {
        try {
            return config.getOptionalValue(key, String.class).orElse(fallback);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private Integer optInt(String key) {
        try {
            return config.getOptionalValue(key, Integer.class).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean optBoolean(String key, boolean fallback) {
        try {
            return config.getOptionalValue(key, Boolean.class).orElse(fallback);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
