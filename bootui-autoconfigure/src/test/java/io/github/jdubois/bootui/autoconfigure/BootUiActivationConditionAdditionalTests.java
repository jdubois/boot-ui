package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Additional coverage for {@link BootUiActivationCondition} targeting scenarios not
 * already exercised in {@code BootUiActivationConditionTests}.
 *
 * <ul>
 *   <li>devtools on classpath combined with force-off and disabled-profiles</li>
 *   <li>custom {@code bootui.disabled-profiles} edge cases</li>
 *   <li>invalid {@code bootui.enabled} values (fail-closed behaviour)</li>
 * </ul>
 */
class BootUiActivationConditionAdditionalTests {

    private final ClassLoader classLoader = getClass().getClassLoader();

    // -------------------------------------------------------------------------
    // devtools interactions
    // -------------------------------------------------------------------------

    /**
     * Compiles a stub {@code RestartScope} class into {@code tempDir} and returns a
     * {@link URLClassLoader} whose parent is {@code null} (isolated from the test
     * classpath), so {@link BootUiActivationCondition} sees it as "devtools present".
     */
    private static URLClassLoader buildDevtoolsClassLoader(Path tempDir) throws Exception {
        Path source = tempDir.resolve("org/springframework/boot/devtools/restart/RestartScope.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package org.springframework.boot.devtools.restart;

            public class RestartScope {
            }
            """);
        int result =
                ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", tempDir.toString(), source.toString());
        if (result != 0) {
            throw new IllegalStateException("Failed to compile stub RestartScope");
        }
        return new URLClassLoader(new java.net.URL[] {tempDir.toUri().toURL()}, null);
    }

    @Test
    void devtoolsDoesNotOverrideExplicitOff(@TempDir Path tempDir) throws Exception {
        URLClassLoader devtoolsLoader = buildDevtoolsClassLoader(tempDir);
        try {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("bootui.enabled", "OFF");

            BootUiActivation activation = BootUiActivationCondition.resolve(env, devtoolsLoader);

            assertThat(activation.enabled()).isFalse();
            assertThat(activation.reason()).contains("OFF");
        } finally {
            devtoolsLoader.close();
        }
    }

    @Test
    void devtoolsIsOverriddenByDisabledProfile(@TempDir Path tempDir) throws Exception {
        URLClassLoader devtoolsLoader = buildDevtoolsClassLoader(tempDir);
        try {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("prod");

            BootUiActivation activation = BootUiActivationCondition.resolve(env, devtoolsLoader);

            assertThat(activation.enabled()).isFalse();
            assertThat(activation.reason()).contains("prod");
        } finally {
            devtoolsLoader.close();
        }
    }

    @Test
    void devtoolsPlusExplicitOnActivatesEvenWithDisabledProfile(@TempDir Path tempDir) throws Exception {
        URLClassLoader devtoolsLoader = buildDevtoolsClassLoader(tempDir);
        try {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("prod");
            env.setProperty("bootui.enabled", "ON");

            BootUiActivation activation = BootUiActivationCondition.resolve(env, devtoolsLoader);

            assertThat(activation.enabled()).isTrue();
            assertThat(activation.warnings()).isNotEmpty();
        } finally {
            devtoolsLoader.close();
        }
    }

    @Test
    void devtoolsAbsentAndNoEnabledProfileIsDisabled() {
        MockEnvironment env = new MockEnvironment();
        // no active profiles, no devtools, mode=AUTO

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("no enabled profile");
    }

    // -------------------------------------------------------------------------
    // Custom disabled-profiles edge cases
    // -------------------------------------------------------------------------

    @Test
    void devtoolsClassNameConstantIsCorrect() {
        assertThat(BootUiActivationCondition.DEVTOOLS_CLASS)
                .isEqualTo("org.springframework.boot.devtools.restart.RestartScope");
    }

    @Test
    void multipleCustomDisabledProfilesFirstMatchDisables() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("staging");
        env.setProperty("bootui.disabled-profiles", "staging, uat, preprod");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("staging");
    }

    @Test
    void nonMatchingCustomDisabledProfilesDoesNotDisable() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("bootui.disabled-profiles", "staging, uat");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
    }

    @Test
    void defaultProductionProfileIsAlsoDisabled() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        // use default disabled-profiles (prod + production)

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("production");
    }

    @Test
    void blankDisabledProfilesPropertyFallsBackToDefaults() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("bootui.disabled-profiles", "   ");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        // blank value → falls back to defaults which include "prod"
        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("prod");
    }

    // -------------------------------------------------------------------------
    // Invalid bootui.enabled values — fail closed
    // -------------------------------------------------------------------------

    @Test
    void customDisabledProfileWithOnModeRecordsWarningAndActivates() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("nightly");
        env.setProperty("bootui.disabled-profiles", "nightly");
        env.setProperty("bootui.enabled", "ON");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
        assertThat(activation.warnings()).anyMatch(w -> w.contains("nightly"));
    }

    @Test
    void yesValueActivatesAsYamlBooleanTrue() {
        MockEnvironment env = new MockEnvironment();
        // YAML parses `bootui.enabled: yes` as the boolean true; either form must enable BootUI.
        env.setProperty("bootui.enabled", "YES");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
        assertThat(activation.reason()).contains("ON");
    }

    @Test
    void trueValueActivatesAsYamlBooleanTrue() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        // YAML parses `bootui.enabled: ON` as the boolean true, delivered to the environment as "true".
        env.setProperty("bootui.enabled", "TRUE");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
        assertThat(activation.reason()).contains("ON");
    }

    @Test
    void falseValueDisablesAsYamlBooleanFalse() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        // YAML parses `bootui.enabled: OFF`/`no` as the boolean false, delivered as "false".
        env.setProperty("bootui.enabled", "FALSE");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("OFF");
    }

    @Test
    void numericEnabledValueFailsClosed() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.enabled", "1");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
    }

    @Test
    void invalidEnabledValueReasonMentionsTheSuppliedValue() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.enabled", "bogus");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        // The condition reports the raw supplied value verbatim in the reason.
        assertThat(activation.reason()).containsIgnoringCase("bogus");
    }

    @Test
    void offModeWithNoProfilesDisables() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.enabled", "OFF");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("OFF");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @Test
    void onModeWithNoProfilesActivates() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.enabled", "ON");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
    }

    /**
     * Regression test for #447: YAML parses {@code bootui.enabled: ON} as the boolean {@code true},
     * which previously reached the condition as the string {@code "true"} and was rejected as an
     * invalid value, silently disabling BootUI. Drive the value through the real
     * {@link YamlPropertySourceLoader} to prove the documented YAML switch activates BootUI.
     */
    @Test
    void yamlEnabledOnActivates() throws Exception {
        StandardEnvironment env = environmentFromYaml("bootui:\n  enabled: ON\n");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
        assertThat(activation.reason()).contains("ON");
    }

    @Test
    void yamlEnabledOffDisables() throws Exception {
        StandardEnvironment env = environmentFromYaml("bootui:\n  enabled: OFF\n");
        env.setActiveProfiles("dev");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("OFF");
    }

    private static StandardEnvironment environmentFromYaml(String yaml) throws java.io.IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("test-yaml", new ByteArrayResource(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .forEach(source -> env.getPropertySources().addFirst(source));
        return env;
    }
}
