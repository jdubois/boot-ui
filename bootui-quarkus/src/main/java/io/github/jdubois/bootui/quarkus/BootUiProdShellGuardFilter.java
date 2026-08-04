package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/**
 * Keeps the configured BootUI surface and its private {@code /bootui} mount dark in production, including the parts that are reachable for
 * reasons {@link BootUiQuarkusSafetyFilter} and {@link QuarkusPanelAccessFilter} cannot fix: those two
 * (like the rest of the console) are only wired in dev/test, and the data-bearing {@code /bootui/api/**}
 * endpoints are already unreachable in {@link LaunchMode#NORMAL} simply because nothing registers them —
 * but the shared Vue bundle under {@code META-INF/resources/bootui/} is still served by Quarkus'
 * <strong>built-in</strong> static-resource handler regardless of launch mode. That handler is wired
 * unconditionally by {@code quarkus-vertx-http} for any classpath resource under
 * {@code META-INF/resources/**}, completely independently of this extension's own build steps, and
 * Quarkus offers no build-time mechanism to exclude a single path from it (see
 * {@code BootUiQuarkusProcessor}'s class Javadoc for the full investigation). Left alone, that would leave
 * the empty SPA shell's {@code index.html}/JS/CSS reachable in production, just with no working API behind
 * it — this filter is what turns that into a plain 404, at parity with the Spring adapter (which never
 * registers any BootUI route when inactive, so nothing is reachable there either).
 *
 * <p>This bean is registered by its own, deliberately <strong>always-on</strong> build step
 * ({@code BootUiQuarkusProcessor#registerProdShellGuard}) — unlike every other BootUI bean/resource, which
 * is only wired in dev/test. The launch-mode decision is made once, at construction, from the
 * CDI-injected {@link LaunchMode} (Quarkus' own {@code LaunchModeProducer} always provides this, in every
 * launch mode) and stored in {@link #launchMode}; {@link #handle} is an immediate no-op pass-through
 * whenever that is not {@link LaunchMode#NORMAL}, so dev/{@code @QuarkusTest} behavior (including the
 * shell being served, and everything the shared conformance suite exercises) is entirely unaffected. This
 * single, easy-to-audit check is the reason the security decision lives inside the filter rather than in a
 * build-time gate: it cannot be defeated by accidentally getting a build step's launch-mode polarity
 * backwards, since there is no alternate polarity here at all — the bean is unconditionally present, and
 * only its runtime behavior changes.
 *
 * <p>Registered as a global Vert.x HTTP route filter (via the {@link Filters} event), exactly like
 * {@link BootUiQuarkusSafetyFilter}, so it runs before route dispatch — including before Quarkus' static-
 * resource route — for every request, in every launch mode. It suppresses the normalized configured UI/API
 * paths as well as the fixed classpath mount, while invalid dormant production configuration falls back to
 * safe defaults rather than activating any console route. The {@code quarkus.http.root-path} prefix is
 * stripped before matching (shared {@link QuarkusRootPath} helper), so a host application running under a
 * non-default root-path is still fully covered in production.
 */
@ApplicationScoped
public class BootUiProdShellGuardFilter {

    /** Internal classpath path — always {@code /bootui}; the compiled SPA assets live here. */
    static final String INTERNAL_PATH = "/bootui";

    private static final int PRIORITY = 1000;

    private final LaunchMode launchMode;
    private final String configuredPath;
    private final String configuredApiPath;
    private final String rootPrefix;

    @Inject
    public BootUiProdShellGuardFilter(Config config, LaunchMode launchMode) {
        this.launchMode = launchMode;
        this.configuredPath = QuarkusBootUiPaths.safeUiPath(config);
        this.configuredApiPath = QuarkusBootUiPaths.safeApiPath(config);
        this.rootPrefix = QuarkusBootUiPaths.rootPrefix(config);
    }

    public void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        if (launchMode != LaunchMode.NORMAL) {
            // Dev/test: the console is meant to be fully reachable, so never touch the request.
            rc.next();
            return;
        }

        String path = rc.normalizedPath();
        if (path == null) {
            rc.next();
            return;
        }

        String relativePath = QuarkusRootPath.stripPrefix(path, rootPrefix);
        String internalApiPath = INTERNAL_PATH + "/api";
        if (isBootUiPath(relativePath, configuredPath, configuredApiPath)) {
            // Determine the API path for cache-control header differentiation: use the configuredApiPath
            // for requests at the configured path, internalApiPath for direct internal-path access.
            String apiPath = relativePath.equals(configuredApiPath) || relativePath.startsWith(configuredApiPath + "/")
                    ? configuredApiPath
                    : internalApiPath;
            rc.response().setStatusCode(404);
            if (BootUiSecurityHeaders.removesPragma(relativePath, apiPath, 404)) {
                rc.response().headers().remove(BootUiSecurityHeaders.PRAGMA);
            }
            BootUiSecurityHeaders.headersFor(relativePath, apiPath, 404).forEach((name, value) -> {
                if (BootUiSecurityHeaders.overridesExisting(name)
                        || !rc.response().headers().contains(name)) {
                    rc.response().putHeader(name, value);
                }
            });
            rc.response().end();
            return;
        }
        rc.next();
    }

    /**
     * Returns {@code true} for the whole BootUI surface under both the configured base path and
     * the internal classpath path ({@code /bootui}), so the static Vue assets at their classpath
     * location are suppressed even when a custom {@code bootui.path} is configured.
     */
    static boolean isBootUiPath(String path, String configuredPath, String configuredApiPath) {
        if (path == null) {
            return false;
        }
        return path.equals(configuredPath)
                || path.startsWith(configuredPath + "/")
                || path.equals(configuredApiPath)
                || path.startsWith(configuredApiPath + "/")
                || path.equals(INTERNAL_PATH)
                || path.startsWith(INTERNAL_PATH + "/");
    }

    static boolean isBootUiPath(String path, String configuredPath) {
        return isBootUiPath(path, configuredPath, configuredPath + "/api");
    }
}
