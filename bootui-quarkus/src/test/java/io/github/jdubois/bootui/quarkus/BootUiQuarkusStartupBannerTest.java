package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link BootUiQuarkusStartupBanner}'s pure logic without booting Quarkus: the startup URL builder (port +
 * {@code quarkus.http.root-path} normalization, fixed {@code /bootui} mount) and the {@code bootui.show-banner}
 * gate (default {@code true}, Spring parity). The {@code "BootUI is available at <url>"} message text and the
 * default-on behavior are kept identical to the Spring adapter's banner.
 */
class BootUiQuarkusStartupBannerTest {

    private static Config config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 1000))
                .build();
    }

    @Test
    void buildsRootUrlWhenRootPathIsDefaultSlash() {
        assertThat(BootUiQuarkusStartupBanner.buildStartupUrl(8080, "/")).isEqualTo("http://localhost:8080/bootui");
    }

    @Test
    void includesCustomRootPath() {
        assertThat(BootUiQuarkusStartupBanner.buildStartupUrl(8080, "/app"))
                .isEqualTo("http://localhost:8080/app/bootui");
    }

    @Test
    void stripsTrailingSlashFromRootPath() {
        assertThat(BootUiQuarkusStartupBanner.buildStartupUrl(8081, "/app/"))
                .isEqualTo("http://localhost:8081/app/bootui");
    }

    @Test
    void treatsBlankRootPathAsRoot() {
        assertThat(BootUiQuarkusStartupBanner.buildStartupUrl(9000, "")).isEqualTo("http://localhost:9000/bootui");
    }

    @Test
    void reflectsTheLiveBoundPort() {
        assertThat(BootUiQuarkusStartupBanner.buildStartupUrl(58460, "/")).isEqualTo("http://localhost:58460/bootui");
    }

    @Test
    void showBannerDefaultsToTrueWhenUnset() {
        assertThat(BootUiQuarkusStartupBanner.showBanner(config(Map.of()))).isTrue();
    }

    @Test
    void showBannerHonorsExplicitFalse() {
        assertThat(BootUiQuarkusStartupBanner.showBanner(config(Map.of("bootui.show-banner", "false"))))
                .isFalse();
    }

    @Test
    void showBannerHonorsExplicitTrue() {
        assertThat(BootUiQuarkusStartupBanner.showBanner(config(Map.of("bootui.show-banner", "true"))))
                .isTrue();
    }
}
