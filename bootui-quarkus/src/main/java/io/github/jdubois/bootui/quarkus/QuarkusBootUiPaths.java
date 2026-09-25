package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import org.eclipse.microprofile.config.Config;

/**
 * Single source of truth for normalized Quarkus UI, API, and application-root path composition.
 */
public final class QuarkusBootUiPaths {

    public static final String PATH_KEY = "bootui.path";
    public static final String API_PATH_KEY = "bootui.api-path";

    /** Internal, fixed mount serving BootUI's JAX-RS resources and static assets. */
    private static final String INTERNAL_UI_PATH = BootUiPathNormalizer.DEFAULT_PATH;

    private static final String INTERNAL_API_PATH = INTERNAL_UI_PATH + "/api";

    private QuarkusBootUiPaths() {}

    public static String uiPath(Config config) {
        String configured = config.getConfigValue(PATH_KEY).getRawValue();
        return BootUiPathNormalizer.normalize(configured == null ? BootUiPathNormalizer.DEFAULT_PATH : configured);
    }

    public static String apiPath(Config config) {
        String configured = config.getConfigValue(API_PATH_KEY).getRawValue();
        return BootUiPathNormalizer.normalizeApiPath(configured == null ? uiPath(config) + "/api" : configured);
    }

    public static String applicationPath(Config config, String path) {
        return rootPrefix(config) + path;
    }

    public static String applicationApiPath(Config config) {
        return applicationPath(config, apiPath(config));
    }

    public static void validate(Config config) {
        uiPath(config);
        apiPath(config);
    }

    /**
     * Whether {@code normalizedPath} — a full Vert.x {@link io.vertx.ext.web.RoutingContext#normalizedPath()},
     * which still carries the {@code quarkus.http.root-path} prefix — targets BootUI's own console surface.
     *
     * <p>This is the single matcher every self-traffic exclusion in the Quarkus adapter uses — HTTP-exchange
     * capture, request-failure capture, pre-mapping exception capture, and log-based exception capture — so
     * those capture points can never drift from each other, nor from the access filters that guard the very
     * same surface. It recognizes a BootUI request in all three mount shapes:</p>
     * <ul>
     *   <li>the internal {@code /bootui} and {@code /bootui/api} mounts, which every request ends up on once
     *       {@link BootUiPathRewriteFilter} has rerouted it;</li>
     *   <li>the operator-configured {@code bootui.path} / {@code bootui.api-path} mounts, so a request is
     *       excluded even before that reroute (and regardless of whether the rewrite filter is wired);</li>
     *   <li>any of the above under a non-default {@code quarkus.http.root-path}, whose prefix is stripped
     *       first via {@link QuarkusRootPath} exactly as {@link BootUiQuarkusSafetyFilter} and
     *       {@link QuarkusPanelAccessFilter} strip it.</li>
     * </ul>
     *
     * <p>Matching is a strict {@code /}-delimited prefix match on the request path only, so an unrelated
     * application path such as {@code /bootui-other} or {@code /api/bootui-proxy} is never mistaken for BootUI
     * traffic and stays captured. Invalid configuration degrades to the internal mounts rather than throwing:
     * a capture filter must never disrupt a request, and defaulting narrowly keeps application traffic
     * captured.</p>
     *
     * @param config live MicroProfile configuration, or {@code null} when it cannot be resolved
     * @param normalizedPath the full normalized request path, or {@code null}
     */
    public static boolean isBootUiRequest(Config config, String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return false;
        }
        String relativePath = normalizedPath;
        String uiPath = INTERNAL_UI_PATH;
        String apiPath = INTERNAL_API_PATH;
        if (config != null) {
            try {
                relativePath = QuarkusRootPath.stripPrefix(normalizedPath, rootPrefix(config));
            } catch (RuntimeException exception) {
                relativePath = normalizedPath;
            }
            try {
                uiPath = safeUiPath(config);
                apiPath = safeApiPath(config);
            } catch (RuntimeException exception) {
                uiPath = INTERNAL_UI_PATH;
                apiPath = INTERNAL_API_PATH;
            }
        }
        return isSameOrChild(relativePath, INTERNAL_UI_PATH)
                || isSameOrChild(relativePath, INTERNAL_API_PATH)
                || isSameOrChild(relativePath, uiPath)
                || isSameOrChild(relativePath, apiPath);
    }

    private static boolean isSameOrChild(String path, String basePath) {
        return path != null && basePath != null && (path.equals(basePath) || path.startsWith(basePath + "/"));
    }

    static String rootPrefix(Config config) {
        return QuarkusRootPath.normalize(config.getOptionalValue(QuarkusRootPath.ROOT_PATH_KEY, String.class)
                .orElse("/"));
    }

    static String safeUiPath(Config config) {
        try {
            return uiPath(config);
        } catch (IllegalArgumentException exception) {
            return BootUiPathNormalizer.DEFAULT_PATH;
        }
    }

    static String safeApiPath(Config config) {
        try {
            return apiPath(config);
        } catch (IllegalArgumentException exception) {
            return BootUiPathNormalizer.DEFAULT_PATH + "/api";
        }
    }
}
