package io.github.jdubois.bootui.quarkus4;

import io.github.jdubois.bootui.core.BootUiInfo;
import io.github.jdubois.bootui.core.dto.ActivationStatus;
import io.github.jdubois.bootui.core.dto.OverviewDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.quarkus.runtime.ImageMode;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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

    private final RunIdentity run = RunIdentity.start();

    @Inject
    public QuarkusApplicationInfo(Config config) {
        this.config = config;
    }

    /**
     * Creates this bean when the application starts, so its run ({@code docs/PLAN-v2.md} §5.1) starts with the
     * application rather than when BootUI is first opened. A live reload starts a new application, and so a new run.
     */
    void onStart(@Observes StartupEvent event) {
        // Observing the event is enough to instantiate the bean eagerly.
    }

    /** This application start, as one run. */
    public RunIdentity run() {
        return run;
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
                null,
                run.toDto());
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
        boolean localhostOnly = !optBoolean(BootUiQuarkusSafetyFilter.ALLOW_NON_LOCALHOST_KEY, false);
        boolean nativeRuntime = ImageMode.current() == ImageMode.NATIVE_RUN;
        boolean active = !nativeRuntime || BootUiNativeActivation.isActive(LaunchMode.current());
        return new ActivationStatus(
                active,
                localhostOnly,
                nativeRuntime
                        ? (active
                                ? "BootUI is active in an explicitly enabled local Quarkus native executable."
                                : "BootUI is inactive in this Quarkus native executable.")
                        : "BootUI is active on Quarkus (the console is wired only outside production launch mode).",
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
