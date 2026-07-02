package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.panel.BootUiPanels.Panel;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Set;
import org.eclipse.microprofile.config.Config;

/**
 * Applies per-panel enabled and read-only settings to BootUI API routes — the Quarkus analogue of the
 * Spring adapter's {@code PanelAccessFilter}, at behavioral parity: same config keys
 * ({@code bootui.panels.<id>.enabled} / {@code .read-only}, plus the global {@code bootui.read-only}),
 * same panel resolution via the shared {@link BootUiPanels#byApiPath(String)} registry, and the same
 * canonical JSON 403 body shape ({@code {"error":"BootUI panel access denied","panel":"<id>","reason":"<reason>"}}).
 *
 * <p>Registered as a global Vert.x route filter (via the {@link Filters} event), like
 * {@link BootUiQuarkusSafetyFilter}, so it sees every request before routing. It runs at a
 * <strong>lower</strong> priority than the safety filter ({@value #PRIORITY} vs. the safety filter's
 * {@code 1000}) so the localhost/Host/CSRF checks always evaluate first: a request that fails both the
 * safety guard and panel gating is rejected by the safety filter, which never calls
 * {@link RoutingContext#next()}, so this filter never even runs for it.
 *
 * <p>Only requests under {@code /bootui/api/**} are considered (mirroring the Spring filter's
 * {@code shouldNotFilter}); the static UI shell under {@code /bootui/**} is never gated by a panel toggle.
 * A request path that does not resolve to a registered {@link Panel} — notably {@code GET
 * /bootui/api/overview} (the Overview panel registers no API prefix on purpose), the
 * {@code /bootui/api/panels} manifest endpoint itself, and any future non-panel endpoint — passes through
 * untouched, exactly like the Spring filter.
 */
@ApplicationScoped
public class QuarkusPanelAccessFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final String API_PATH = "/bootui/api";

    /**
     * Runs after the safety filter (priority {@code 1000}) so localhost/Host/CSRF rejection always wins,
     * but before {@link QuarkusHttpExchangeCaptureFilter} (priority {@code 900}) has no ordering
     * requirement either way since it only records, never short-circuits.
     */
    private static final int PRIORITY = 950;

    private final Config config;
    private final QuarkusPanelAccessConfig accessConfig;

    @Inject
    public QuarkusPanelAccessFilter(Config config) {
        this.config = config;
        this.accessConfig = new QuarkusPanelAccessConfig(config);
    }

    public void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        String apiRelativePath = apiRelativePath(rc);
        if (apiRelativePath == null) {
            rc.next();
            return;
        }

        Panel panel = BootUiPanels.byApiPath(apiRelativePath).orElse(null);
        if (panel == null) {
            rc.next();
            return;
        }

        if (!accessConfig.isPanelEnabled(panel.id())) {
            writeBlockedResponse(rc, panel.id(), accessConfig.panelDisabledReason(panel.id()));
            return;
        }

        String method = rc.request().method().name();
        if (panel.actionCapable() && !SAFE_METHODS.contains(method) && accessConfig.isPanelReadOnly(panel.id())) {
            writeBlockedResponse(rc, panel.id(), accessConfig.panelReadOnlyReason(panel.id()));
            return;
        }

        rc.next();
    }

    /**
     * Resolves the request path relative to {@code /bootui/api}, after stripping the configured
     * {@code quarkus.http.root-path} prefix (shared with {@link BootUiQuarkusSafetyFilter} via
     * {@link QuarkusRootPath}). Returns {@code null} when the request is not under {@code /bootui/api} at
     * all (mirrors the Spring filter's {@code shouldNotFilter}/{@code isBootUiApiRequest} check), including
     * requests to the static UI shell under {@code /bootui/**}.
     */
    private String apiRelativePath(RoutingContext rc) {
        String path = QuarkusRootPath.stripPrefix(rc.normalizedPath(), QuarkusRootPath.normalize(rootPath()));
        if (path == null) {
            return null;
        }
        if (path.equals(API_PATH)) {
            return "/";
        }
        if (!path.startsWith(API_PATH + "/")) {
            return null;
        }
        return path.substring(API_PATH.length());
    }

    private String rootPath() {
        return config.getOptionalValue(QuarkusRootPath.ROOT_PATH_KEY, String.class)
                .orElse("/");
    }

    private void writeBlockedResponse(RoutingContext rc, String panelId, String reason) {
        HttpServerResponse response = rc.response();
        response.setStatusCode(403)
                .putHeader("Content-Type", "application/json")
                .end("{\"error\":\"" + escape("BootUI panel access denied") + "\",\"panel\":\"" + escape(panelId)
                        + "\",\"reason\":\"" + escape(reason) + "\"}");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
