package io.github.jdubois.bootui.quarkus4;

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
        assertThat(QuarkusBootUiPaths.applicationPath(configured, "/")).isEqualTo("/host/");
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

    // --- self-traffic matching ----------------------------------------------------------------------

    @Test
    void matchesBootUiRequestsUnderTheDefaultRootPath() {
        Config config = config(Map.of());

        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/bootui")).isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/bootui/")).isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/bootui/assets/app.js"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/bootui/api/overview"))
                .isTrue();
    }

    @Test
    void matchesBootUiRequestsUnderANonDefaultRootPath() {
        Config config = config(Map.of("quarkus.http.root-path", "/app"));

        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/bootui")).isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/bootui/")).isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/bootui/api/exceptions"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/orders")).isFalse();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app")).isFalse();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/")).isFalse();
    }

    @Test
    void matchesACustomMountBeforeAndAfterTheInternalReroute() {
        Config config = config(Map.of(
                "bootui.path", "/dev-console",
                "quarkus.http.root-path", "/app"));

        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/dev-console"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/dev-console/api/overview"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/bootui/api/overview"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/dev-consoles"))
                .isFalse();
    }

    @Test
    void matchesASeparatelyMountedApiPath() {
        Config config = config(Map.of(
                "bootui.path", "/console",
                "bootui.api-path", "/internal/bootui-api"));

        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/internal/bootui-api"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/internal/bootui-api/mappings"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/internal/bootui-apis"))
                .isFalse();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/internal")).isFalse();
    }

    @Test
    void neverMatchesApplicationPathsThatMerelyLookLikeBootUi() {
        Config config = config(Map.of("quarkus.http.root-path", "/app"));

        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/bootui-other"))
                .isFalse();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/api/bootui-proxy"))
                .isFalse();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/application/bootui"))
                .isFalse();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/orders/bootui"))
                .isFalse();
        // Vert.x hands filters an already-normalized path: percent-encoded unreserved characters are decoded
        // and '.'/'..'/'//' segments are collapsed, so those cannot be used to evade the match. An encoded
        // path separator is deliberately left encoded, and such a path never routes to BootUI either.
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config, "/app/bootui%2Fapi"))
                .isFalse();
    }

    @Test
    void normalizesTheConfiguredRootPathBeforeMatching() {
        assertThat(QuarkusBootUiPaths.isBootUiRequest(
                        config(Map.of("quarkus.http.root-path", "/app/")), "/app/bootui/api/overview"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(
                        config(Map.of("quarkus.http.root-path", "app")), "/app/bootui/api/overview"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config(Map.of("quarkus.http.root-path", "/")), "/bootui"))
                .isTrue();
    }

    @Test
    void degradesToInternalMountsWithoutUsableConfiguration() {
        assertThat(QuarkusBootUiPaths.isBootUiRequest(null, "/bootui/api/overview"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(null, "/orders")).isFalse();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(null, null)).isFalse();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(config(Map.of()), "  ")).isFalse();

        Config invalid = config(Map.of("bootui.path", "", "bootui.api-path", ""));
        assertThat(QuarkusBootUiPaths.isBootUiRequest(invalid, "/bootui/api/overview"))
                .isTrue();
        assertThat(QuarkusBootUiPaths.isBootUiRequest(invalid, "/orders")).isFalse();
    }

    private static Config config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }
}
