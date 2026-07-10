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
 * Keeps the whole {@code /bootui} surface dark in production, including the parts that are reachable for
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
 * resource route — for every request, in every launch mode. The {@code quarkus.http.root-path} prefix is
 * stripped before matching (shared {@link QuarkusRootPath} helper), so a host application running under a
 * non-default root-path is still fully covered in production.
 */
@ApplicationScoped
public class BootUiProdShellGuardFilter {

    private static final String BASE_PATH = "/bootui";

    private static final String API_PATH = BASE_PATH + "/api";

    /**
     * Run early, before route dispatch (including the static-resource route), matching
     * {@link BootUiQuarkusSafetyFilter}'s priority. The exact value relative to the other BootUI filters
     * does not matter: this filter only ever does meaningful work in {@link LaunchMode#NORMAL}, where
     * {@link BootUiQuarkusSafetyFilter} and {@link QuarkusPanelAccessFilter} are never wired at all, and in
     * every other launch mode this filter is an immediate pass-through.
     */
    private static final int PRIORITY = 1000;

    private final Config config;
    private final LaunchMode launchMode;

    @Inject
    public BootUiProdShellGuardFilter(Config config, LaunchMode launchMode) {
        this.config = config;
        this.launchMode = launchMode;
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
        // Cheap pre-check: unlike BootUiQuarkusSafetyFilter (which is prod-dark), this filter is active
        // for every request in production, so avoid the root-path-aware Config lookup below for the vast
        // majority of unrelated requests. A path that cannot possibly contain the console under any
        // root-path prefix is let through immediately; a false positive here just falls through to the
        // precise check, which is always correct.
        if (path == null || !path.contains(BASE_PATH)) {
            rc.next();
            return;
        }

        String relativePath = QuarkusRootPath.stripPrefix(path, QuarkusRootPath.normalize(rootPath()));
        if (isBootUiPath(relativePath)) {
            rc.response().setStatusCode(404);
            if (BootUiSecurityHeaders.removesPragma(relativePath, API_PATH, 404)) {
                rc.response().headers().remove(BootUiSecurityHeaders.PRAGMA);
            }
            BootUiSecurityHeaders.headersFor(relativePath, API_PATH, 404).forEach((name, value) -> {
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
     * Returns {@code true} for the whole BootUI surface — the static shell and {@code /bootui/api/**}
     * alike — using the same strict boundary check as {@link BootUiQuarkusSafetyFilter#isBootUiRequest}
     * (an exact match or a {@code /}-delimited sub-path), so a lookalike path such as {@code /bootui-other}
     * is left alone.
     */
    static boolean isBootUiPath(String path) {
        if (path == null) {
            return false;
        }
        return path.equals(BASE_PATH) || path.startsWith(BASE_PATH + "/");
    }

    private String rootPath() {
        return config.getOptionalValue(QuarkusRootPath.ROOT_PATH_KEY, String.class)
                .orElse("/");
    }
}
