package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class BootUiActivationConditionTests {

    private final ClassLoader classLoader = getClass().getClassLoader();

    @Test
    void autoEnablesOnEnabledProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
        assertThat(activation.reason()).contains("dev");
    }

    @Test
    void autoDisablesWithoutEnabledProfileAndWithoutDevtools() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("staging");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("no enabled profile");
    }

    @Test
    void disabledProfileForcesOffByDefault() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "dev");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("prod");
    }

    @Test
    void enabledOnOverridesDisabledProfileButRecordsWarning() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("bootui.enabled", "ON");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
        assertThat(activation.warnings()).isNotEmpty();
        assertThat(activation.warnings().get(0)).contains("prod");
    }

    @Test
    void enabledOffForcesDisabled() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("bootui.enabled", "OFF");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("OFF");
    }

    @Test
    void invalidEnabledModeFailsClosedEvenWithEnabledProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("bootui.enabled", "maybe");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("invalid bootui.enabled");
    }

    @Test
    void modeIsCaseInsensitive() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.enabled", " on ");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
    }

    @Test
    void customEnabledProfilesAreHonored() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("sandbox");
        env.setProperty("bootui.enabled-profiles", "sandbox, testing");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
        assertThat(activation.reason()).contains("sandbox");
    }

    @Test
    void customDisabledProfilesAreHonored() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("sandbox");
        env.setProperty("bootui.disabled-profiles", "sandbox");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isFalse();
        assertThat(activation.reason()).contains("sandbox");
    }

    @Test
    void blankEnabledProfilesPropertyFallsBackToDefaults() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        env.setProperty("bootui.enabled-profiles", "   ");

        BootUiActivation activation = BootUiActivationCondition.resolve(env, classLoader);

        assertThat(activation.enabled()).isTrue();
        assertThat(activation.reason()).contains("local");
    }

    @Test
    void autoEnablesWhenDevtoolsIsPresent(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("org/springframework/boot/devtools/restart/RestartScope.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package org.springframework.boot.devtools.restart;

            public class RestartScope {
            }
            """);
        int result =
                ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", tempDir.toString(), source.toString());
        assertThat(result).isZero();

        try (URLClassLoader devtoolsClassLoader =
                new URLClassLoader(new java.net.URL[] {tempDir.toUri().toURL()}, null)) {
            BootUiActivation activation = BootUiActivationCondition.resolve(new MockEnvironment(), devtoolsClassLoader);

            assertThat(activation.enabled()).isTrue();
            assertThat(activation.reason()).contains("devtools");
        }
    }
}
