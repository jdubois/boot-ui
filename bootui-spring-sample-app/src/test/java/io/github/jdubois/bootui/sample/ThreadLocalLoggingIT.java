package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ThreadLocalLoggingIT {

    @Test
    void aRealLogbackFirstLogWithTenantSetNullIsNotAnApplicationLeak() throws Exception {
        String agent = System.getProperty("bootui.agent.jar");
        assertThat(agent).isNotBlank();
        Path directory = Path.of("target", "thread-local-logging");
        Files.createDirectories(directory);
        for (String mode : List.of("unregistered", "registered")) {
            Path log = directory.resolve(mode + ".log");
            Process process = new ProcessBuilder(List.of(
                            Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            "-Xmx256m",
                            "-javaagent:" + agent,
                            ClassPathArgumentFile.write(directory),
                            ThreadLocalLoggingScenario.class.getName(),
                            mode))
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            try {
                assertThat(process.waitFor(90, TimeUnit.SECONDS))
                        .as("bounded first-log scenario: %s", Files.readString(log))
                        .isTrue();
                assertThat(process.exitValue()).as(Files.readString(log)).isZero();
                assertThat(Files.readString(log))
                        .contains(
                                "unregistered".equals(mode)
                                        ? "BEFORE: first console log"
                                        : "AFTER: no nulled-request row")
                        .doesNotContain("tenant-secret-nulled");
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            }
        }
    }
}
