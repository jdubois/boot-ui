package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Publishes normalized BootUI route properties before Spring creates its handler mappings.
 */
public final class BootUiPathPropertySource {

    static final String PROPERTY_SOURCE_NAME = "bootUiNormalizedPaths";

    private BootUiPathPropertySource() {}

    public static void apply(ConfigurableEnvironment environment) {
        String path = BootUiPathNormalizer.normalize(
                environment.getProperty("bootui.path", BootUiPathNormalizer.DEFAULT_PATH));
        String configuredApiPath = environment.getProperty("bootui.api-path");
        String apiPath = configuredApiPath == null ? path + "/api" : normalizeApiPath(configuredApiPath);

        Map<String, Object> normalizedPaths = new LinkedHashMap<>();
        normalizedPaths.put("bootui.path", path);
        normalizedPaths.put("bootui.api-path", apiPath);

        environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, normalizedPaths));
    }

    private static String normalizeApiPath(String apiPath) {
        return BootUiPathNormalizer.normalizeApiPath(apiPath);
    }
}
