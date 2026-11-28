package io.github.jdubois.bootui.quarkus4.nativeconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.SysPropConfigSource;
import io.smallrye.config.common.MapBackedConfigSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class BootUiNativeActivationTest {
    private static final Map<String, String> LOCAL_ON =
            Map.of("BOOTUI_ENABLED", "ON", "SMALLRYE_CONFIG_PROFILE", "local");

    @Test
    void acceptsLiteralRuntimeEnvironmentButNotProductionOrInheritedLocalProfiles() {
        SmallRyeConfig config = config(new EnvConfigSource(LOCAL_ON, 300));
        assertThat(evaluate(config, List.of("local"))).isTrue();
        assertThat(evaluate(config, List.of("local", "child", "PRODUCTION"))).isFalse();
        assertThat(evaluate(config, List.of("prod", "local"))).isFalse();
        assertThat(evaluate(
                        config(new EnvConfigSource(
                                Map.of("BOOTUI_ENABLED", "ON", "SMALLRYE_CONFIG_PROFILE", "child"), 300)),
                        List.of("child", "local")))
                .isFalse();
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void acceptsRuntimeSystemPropertiesButRejectsDuplicateStandardSourceNames() {
        String mode = System.getProperty("bootui.enabled");
        String profile = System.getProperty(SmallRyeConfig.SMALLRYE_CONFIG_PROFILE);
        try {
            System.setProperty("bootui.enabled", "ON");
            System.setProperty(SmallRyeConfig.SMALLRYE_CONFIG_PROFILE, "local");
            assertThat(evaluate(config(new SysPropConfigSource()), List.of("local")))
                    .isTrue();
            assertThat(evaluate(
                            config(
                                    new SysPropConfigSource(),
                                    new EnvConfigSource(
                                            Map.of("BOOTUI_ENABLED", "OFF", "SMALLRYE_CONFIG_PROFILE", "production"),
                                            300)),
                            List.of("local")))
                    .isTrue();
            assertThat(evaluate(config(new SysPropConfigSource(), new SysPropConfigSource()), List.of("local")))
                    .isFalse();
            System.setProperty("bootui.enabled", "OFF");
            assertThat(evaluate(
                            config(new SysPropConfigSource(), new EnvConfigSource(LOCAL_ON, 300)), List.of("local")))
                    .isFalse();
            System.setProperty("bootui.enabled", "ON");
            System.setProperty(SmallRyeConfig.SMALLRYE_CONFIG_PROFILE, "production");
            assertThat(evaluate(
                            config(new SysPropConfigSource(), new EnvConfigSource(LOCAL_ON, 300)),
                            List.of("production")))
                    .isFalse();
        } finally {
            restore("bootui.enabled", mode);
            restore(SmallRyeConfig.SMALLRYE_CONFIG_PROFILE, profile);
        }
    }

    @Test
    void filesAndSourceNameSpoofsCannotProvideActivationAndHigherPriorityOffStillDenies() {
        ConfigSource spoof = new MapBackedConfigSource(
                "SysPropConfigSource",
                Map.of("bootui.enabled", "ON", SmallRyeConfig.SMALLRYE_CONFIG_PROFILE, "local"),
                400) {};
        assertThat(evaluate(config(spoof), List.of("local"))).isFalse();
        ConfigSource off = new MapBackedConfigSource("application-file", Map.of("bootui.enabled", "OFF"), 1000) {};
        assertThat(evaluate(config(new EnvConfigSource(LOCAL_ON, 300), off), List.of("local")))
                .isFalse();
        ConfigSource duplicate = new MapBackedConfigSource("EnvConfigSource", Map.of("bootui.enabled", "ON"), 100) {};
        assertThat(evaluate(config(new EnvConfigSource(LOCAL_ON, 300), duplicate), List.of("local")))
                .isFalse();
    }

    @Test
    void resolvedExpressionsAreNotLiteralRuntimeOptIn() {
        SmallRyeConfig config = config(new EnvConfigSource(
                Map.of("BOOTUI_ENABLED", "${BOOTUI_FIXTURE_MODE:ON}", "SMALLRYE_CONFIG_PROFILE", "local"), 300));
        List<String> warnings = new ArrayList<>();
        assertThat(BootUiNativeActivation.evaluate(config, List.of("local"), warnings::add))
                .isFalse();
        assertThat(warnings).singleElement().asString().contains("requires runtime system properties");
    }

    @Test
    void literalOptInKeepsEnumNormalizationAndFrameworkProfileListParsing() {
        for (String mode : List.of("ON", "on", " ON ")) {
            for (String profile : List.of("local", "local,", ",local")) {
                assertThat(evaluate(
                                config(new EnvConfigSource(
                                        Map.of("BOOTUI_ENABLED", mode, "SMALLRYE_CONFIG_PROFILE", profile), 300)),
                                List.of("local")))
                        .isTrue();
            }
        }
    }

    @Test
    void malformedActivationReportsOnlyOneValueFreeDiagnostic() {
        for (String mode : List.of("", "private-invalid-value", "${}", "${")) {
            SmallRyeConfig config = config(
                    new EnvConfigSource(Map.of("BOOTUI_ENABLED", mode, "SMALLRYE_CONFIG_PROFILE", "local"), 300));
            List<String> warnings = new ArrayList<>();
            assertThat(BootUiNativeActivation.evaluate(config, List.of("local"), warnings::add))
                    .isFalse();
            assertThat(warnings)
                    .containsExactly(
                            "BootUI is inactive: invalid bootui.enabled configuration (fail-closed). Use AUTO, ON, or OFF.");
        }
    }

    private static SmallRyeConfig config(ConfigSource... sources) {
        return new SmallRyeConfigBuilder()
                .withSources(sources)
                .addDefaultInterceptors()
                .build();
    }

    private static boolean evaluate(SmallRyeConfig config, List<String> profiles) {
        return BootUiNativeActivation.evaluate(config, profiles, ignored -> {});
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
