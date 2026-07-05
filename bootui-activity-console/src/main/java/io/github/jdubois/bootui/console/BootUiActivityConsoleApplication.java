package io.github.jdubois.bootui.console;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the BootUI Activity Console: a standalone Spring WebFlux application that aggregates
 * Live Activity entries forwarded over HTTP from any BootUI-enabled instance on localhost (Spring Boot or
 * Quarkus), so a cross-service call shows up as one correlated feed instead of being scattered across each
 * instance's own dashboard.
 *
 * <p>Unlike every other application in this repository, the console is <em>not</em> a demo host
 * application that happens to embed BootUI via a starter — it <em>is</em> a purpose-built BootUI
 * distribution, wired directly against {@code bootui-engine}/{@code bootui-core} rather than
 * {@code bootui-spring-autoconfigure} (see the package-level Javadoc in {@code console.web} for why). It
 * serves the same compiled Vue UI as every other adapter, restricted by its own minimal panels manifest
 * ({@link io.github.jdubois.bootui.console.web.ConsolePanelsController}) to show only the Live Activity
 * panel.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BootUiActivityConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(BootUiActivityConsoleApplication.class, args);
    }
}
