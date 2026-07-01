package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.sample.catalog.SampleSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties(SampleSettings.class)
public class BootUiSampleApplication {

    private static final Logger log = LoggerFactory.getLogger(BootUiSampleApplication.class);

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BootUiSampleApplication.class);
        composeFileDefault()
                .ifPresent(composeFile ->
                        application.setDefaultProperties(Map.of("spring.docker.compose.file", composeFile.toString())));
        application.run(args);
    }

    static Optional<Path> composeFileDefault() {
        return composeFileDefault(Path.of("").toAbsolutePath());
    }

    static Optional<Path> composeFileDefault(Path workingDirectory) {
        if (Files.isRegularFile(workingDirectory.resolve("compose.yaml"))) {
            return Optional.empty();
        }
        Path moduleComposeFile = workingDirectory.resolve(Path.of("bootui-spring-sample-app", "compose.yaml"));
        return Files.isRegularFile(moduleComposeFile) ? Optional.of(moduleComposeFile) : Optional.empty();
    }

    @EventListener(ApplicationReadyEvent.class)
    void logStartupTime(ApplicationReadyEvent event) {
        log.info("Sample app started up in {} ms", event.getTimeTaken().toMillis());
    }
}
