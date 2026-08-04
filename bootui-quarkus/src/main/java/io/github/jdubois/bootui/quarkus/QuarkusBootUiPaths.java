package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import org.eclipse.microprofile.config.Config;

/**
 * Single source of truth for normalized Quarkus UI, API, and application-root path composition.
 */
public final class QuarkusBootUiPaths {

    public static final String PATH_KEY = "bootui.path";
    public static final String API_PATH_KEY = "bootui.api-path";

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

    public static String applicationUiPath(Config config) {
        return applicationPath(config, uiPath(config));
    }

    public static String applicationApiPath(Config config) {
        return applicationPath(config, apiPath(config));
    }

    public static void validate(Config config) {
        uiPath(config);
        apiPath(config);
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
