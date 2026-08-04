package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class QuarkusBootUiPathsTest {

    @Test
    void defaultsAndNormalizesPaths() {
        assertThat(QuarkusBootUiPaths.uiPath(config(Map.of()))).isEqualTo("/bootui");
        assertThat(QuarkusBootUiPaths.apiPath(config(Map.of()))).isEqualTo("/bootui/api");

        Config configured = config(Map.of(
                "bootui.path", " /dev-console/// ",
                "bootui.api-path", " /internal/bootui-api/ ",
                "quarkus.http.root-path", "/host/"));
        assertThat(QuarkusBootUiPaths.uiPath(configured)).isEqualTo("/dev-console");
        assertThat(QuarkusBootUiPaths.apiPath(configured)).isEqualTo("/internal/bootui-api");
        assertThat(QuarkusBootUiPaths.applicationPath(configured, QuarkusBootUiPaths.uiPath(configured)))
                .isEqualTo("/host/dev-console");
    }

    @Test
    void derivesApiPathFromNormalizedUiPath() {
        Config config = config(Map.of("bootui.path", "/dev-console/"));

        assertThat(QuarkusBootUiPaths.apiPath(config)).isEqualTo("/dev-console/api");
    }

    @Test
    void strictAccessRejectsInvalidPathWhileProductionFallbackIsSafe() {
        Config config = config(Map.of("bootui.path", ""));

        assertThatThrownBy(() -> QuarkusBootUiPaths.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootui.path");
        assertThat(QuarkusBootUiPaths.safeUiPath(config)).isEqualTo("/bootui");
        assertThat(QuarkusBootUiPaths.safeApiPath(config)).isEqualTo("/bootui/api");
    }

    private static Config config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }
}
