package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiActivation;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.ActivationStatus;
import io.github.jdubois.bootui.core.dto.OverviewDto;
import io.github.jdubois.bootui.core.BootUiInfo;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/overview")
public class OverviewController {

    private final Environment environment;

    private final BootUiActivation activation;

    private final BootUiProperties properties;

    public OverviewController(Environment environment, BootUiActivation activation, BootUiProperties properties) {
        this.environment = environment;
        this.activation = activation;
        this.properties = properties;
    }

    @GetMapping
    public OverviewDto overview() {
        String name = environment.getProperty("spring.application.name", "application");
        String webType = detectWebType();
        Integer port =
                parseInt(environment.getProperty("local.server.port", environment.getProperty("server.port", "8080")));
        Integer managementPort = parseInt(environment.getProperty("management.server.port"));
        Long startupMs = parseLong(environment.getProperty("spring.boot.application.startup.time"));

        return new OverviewDto(
                BootUiInfo.VERSION,
                name,
                SpringBootVersion.getVersion(),
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                Arrays.asList(environment.getActiveProfiles()),
                Arrays.asList(environment.getDefaultProfiles()),
                webType,
                port,
                managementPort,
                environment.getProperty("server.servlet.context-path", ""),
                startupMs,
                new ActivationStatus(
                        activation.enabled(),
                        !properties.isAllowNonLocalhost(),
                        activation.reason(),
                        activation.warnings() == null ? List.of() : activation.warnings()),
                detectOpenApiUrl());
    }

    private String detectOpenApiUrl() {
        try {
            Class.forName("org.springdoc.core.utils.SpringDocUtils");
            return environment.getProperty("springdoc.swagger-ui.path", "/swagger-ui/index.html");
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc");
                return environment.getProperty("springdoc.swagger-ui.path", "/swagger-ui/index.html");
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }
    }

    private String detectWebType() {
        try {
            Class.forName("org.springframework.web.reactive.DispatcherHandler");
            try {
                Class.forName("org.springframework.web.servlet.DispatcherServlet");
                return "SERVLET";
            } catch (ClassNotFoundException e) {
                return "REACTIVE";
            }
        } catch (ClassNotFoundException e) {
            return "SERVLET";
        }
    }

    private Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
