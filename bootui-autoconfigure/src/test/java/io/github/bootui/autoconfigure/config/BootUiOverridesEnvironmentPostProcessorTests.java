package io.github.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

class BootUiOverridesEnvironmentPostProcessorTests {

    private final BootUiOverridesEnvironmentPostProcessor processor =
            new BootUiOverridesEnvironmentPostProcessor();

    @Test
    void registersHighestPrecedencePropertySource(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("overrides.properties");
        Files.writeString(file, "server.port=9090\n");

        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.overrides-file", file.toString());
        env.setProperty("server.port", "8080"); // existing source — should be shadowed

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getPropertySources().contains(BootUiOverridesPropertySource.NAME)).isTrue();
        assertThat(env.getProperty("server.port")).isEqualTo("9090");
        assertThat(env.getPropertySources().iterator().next().getName())
                .isEqualTo(BootUiOverridesPropertySource.NAME);
    }

    @Test
    void rerunRefreshesPropertySource(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("overrides.properties");
        Files.writeString(file, "k=first\n");
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.overrides-file", file.toString());

        processor.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("k")).isEqualTo("first");

        Files.writeString(file, "k=second\n");
        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("k")).isEqualTo("second");
        long count = env.getPropertySources().stream()
                .filter(s -> BootUiOverridesPropertySource.NAME.equals(s.getName()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void registersEmptySourceWhenFileMissing(@TempDir Path tmp) {
        Path file = tmp.resolve("does-not-exist.properties");
        StandardEnvironment env = new StandardEnvironment();
        env.getSystemProperties().put("bootui.overrides-file", file.toString());

        processor.postProcessEnvironment(env, new SpringApplication());

        BootUiOverridesPropertySource src = (BootUiOverridesPropertySource)
                env.getPropertySources().get(BootUiOverridesPropertySource.NAME);
        assertThat(src).isNotNull();
        assertThat(src.mutableSource()).isEmpty();
    }

    @Test
    void orderIsBeforeConfigData() {
        assertThat(processor.getOrder()).isEqualTo(BootUiOverridesEnvironmentPostProcessor.ORDER);
    }
}
