package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class BootUiOverridesLoggingListenerTests {

    @Test
    void warnsAfterConfigDataResolvesInactiveProfileWithoutLoggingValues(@TempDir Path tmp, CapturedOutput output)
            throws Exception {
        Path file = tmp.resolve("overrides.properties");
        Files.writeString(file, "sample.greeting=never-log-this-value\nbootui.enabled=ON\n");
        try (ConfigurableApplicationContext context = start(file, "prod")) {
            assertThat(context.getEnvironment().getProperty("sample.greeting")).isEqualTo("never-log-this-value");
            assertThat(context.getEnvironment().getProperty("bootui.enabled")).isNull();
            assertThat(output)
                    .contains("WARN", file.toString(), "while BootUI is inactive", "Ignored 1 protected")
                    .doesNotContain("never-log-this-value");
        }
    }

    @Test
    void reportsActiveOverridesAtInfo(@TempDir Path tmp, CapturedOutput output) throws Exception {
        Path file = tmp.resolve("overrides.properties");
        Files.writeString(file, "sample.greeting=never-log-this-value\n");
        try (ConfigurableApplicationContext ignored = start(file, "dev")) {
            assertThat(output)
                    .contains("INFO", "Loaded 1 persisted BootUI override(s)", file.toString())
                    .doesNotContain("while BootUI is inactive", "never-log-this-value");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.config.import", "spring.config.import[0]"})
    void protectedBootstrapSelectorsCannotImportOrActivateSafetyPolicy(
            String importKey, @TempDir Path tmp, CapturedOutput output) throws Exception {
        Path imported = tmp.resolve("unsafe.properties");
        Files.writeString(imported, "bootui.enabled=ON\nimported.probe=must-not-load\n");
        Path file = tmp.resolve("overrides.properties");
        Files.writeString(
                file,
                "spring.profiles.active=dev\nSPRING_PROFILES_INCLUDE_0=local\n"
                        + importKey + "=" + imported.toUri() + "\n"
                        + "management.endpoints.web.exposure.include=*\n"
                        + "server.port=9090\nsample.greeting=still-applies\n");

        try (ConfigurableApplicationContext context = start(file, "prod")) {
            var environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).containsExactly("prod");
            assertThat(environment.getProperty("bootui.enabled")).isNull();
            assertThat(environment.getProperty("imported.probe")).isNull();
            assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                    .isNull();
            assertThat(environment.getProperty("server.port")).isEqualTo("9090");
            assertThat(environment.getProperty("sample.greeting")).isEqualTo("still-applies");
            assertThat(output)
                    .contains("Ignored 4 protected", "while BootUI is inactive")
                    .doesNotContain("must-not-load", "still-applies");
        }
    }

    private ConfigurableApplicationContext start(Path file, String profile) {
        SpringApplication application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(
                "--bootui.overrides-file=" + file,
                "--spring.profiles.active=" + profile,
                "--bootui.force-web=false",
                "--spring.main.banner-mode=off");
    }

    static class EmptyApplication {}
}
