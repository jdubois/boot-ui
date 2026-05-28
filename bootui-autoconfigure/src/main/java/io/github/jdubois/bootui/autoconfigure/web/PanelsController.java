package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.PanelDto;
import io.github.jdubois.bootui.core.BootUiDtos.PanelsReport;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.startup.StartupEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/panels")
public class PanelsController {

    // Matches both plain application-<profile>.{properties,yml,yaml} names and
    // Spring Boot's "Config resource 'file ... application-<profile>.yml'" source names.
    private static final Pattern PROFILE_SOURCE_PATTERN = Pattern.compile(
            "(?:application-|Config resource 'file [^']*application-)([\\w-]+)(?:\\.properties|\\.ya?ml)");

    private final ApplicationContext applicationContext;
    private final Environment environment;
    private final BootUiProperties properties;

    public PanelsController(
            ApplicationContext applicationContext, Environment environment, BootUiProperties properties) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.properties = properties;
    }

    @GetMapping
    public PanelsReport panels() {
        return new PanelsReport(List.of(
                panel("overview", "Overview", true, null),
                panel(
                        "startup",
                        "Startup Timeline",
                        beanPresent(StartupEndpoint.class),
                        "Actuator startup endpoint not available"),
                panel("memory", "Memory", true, null),
                panel("health", "Health", beanPresent(HealthEndpoint.class), "Actuator health endpoint not available"),
                panel(
                        "metrics",
                        "Metrics",
                        classPresent("io.micrometer.core.instrument.MeterRegistry")
                                && beanPresent(MetricsEndpoint.class),
                        "Actuator metrics endpoint or MeterRegistry not available"),
                panel(
                        "conditions",
                        "Conditions",
                        beanPresent(ConditionsReportEndpoint.class),
                        "Actuator conditions endpoint not available"),
                panel("beans", "Beans", beanPresent(BeansEndpoint.class), "Actuator beans endpoint not available"),
                panel(
                        "mappings",
                        "Mappings",
                        beanPresent(MappingsEndpoint.class),
                        "Actuator mappings endpoint not available"),
                panel("config", "Configuration", true, null),
                panel(
                        "profiles",
                        "Profile Diff",
                        profilesAvailable(),
                        "No active profiles or profile-specific config sources available"),
                panel(
                        "loggers",
                        "Loggers",
                        beanPresent(LoggersEndpoint.class),
                        "Actuator loggers endpoint not available"),
                panel(
                        "log-tail",
                        "Log Tail",
                        classPresent("ch.qos.logback.classic.Logger"),
                        "Logback not on the classpath"),
                panel("http-probe", "HTTP Probe", true, null),
                panel("devtools", "DevTools", devToolsPresent(), "Spring Boot DevTools not on the classpath"),
                panel(
                        "dev-services",
                        "Dev Services",
                        devServicesPresent(),
                        "Docker Compose or Testcontainers not on the classpath"),
                panel("scheduled", "Scheduled Tasks", scheduledAvailable(), "Scheduling is not enabled"),
                panel(
                        "data",
                        "Data",
                        classPresent("org.springframework.data.repository.Repository"),
                        "Spring Data not on the classpath"),
                panel("cache", "Cache", beanPresent(CacheManager.class), "No CacheManager beans are available"),
                panel("traces", "Traces", properties.getTelemetry().isEnabled(), "Telemetry receiver is disabled"),
                panel("ai", "AI Usage", aiAvailable(), aiUnavailableReason()),
                panel("copilot", "Copilot", copilotAvailable(), copilotUnavailableReason()),
                panel(
                        "security",
                        "Security",
                        classPresent("org.springframework.security.web.SecurityFilterChain"),
                        "Spring Security not on the classpath"),
                panel("vulnerabilities", "Vulnerabilities", true, null)));
    }

    private PanelDto panel(String id, String title, boolean available, String unavailableReason) {
        return new PanelDto(id, title, available, available ? null : unavailableReason);
    }

    private boolean beanPresent(Class<?> type) {
        return applicationContext.getBeanNamesForType(type).length > 0;
    }

    private boolean classPresent(String className) {
        return ClassUtils.isPresent(className, getClass().getClassLoader());
    }

    private boolean profilesAvailable() {
        return environment.getActiveProfiles().length > 0 || hasProfileSpecificSources();
    }

    private boolean hasProfileSpecificSources() {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return false;
        }
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            String name = propertySource.getName();
            if (name != null && PROFILE_SOURCE_PATTERN.matcher(name).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean devToolsPresent() {
        return classPresent("org.springframework.boot.devtools.RemoteSpringApplication")
                || classPresent("org.springframework.boot.devtools.restart.Restarter");
    }

    private boolean devServicesPresent() {
        return classPresent("org.springframework.boot.docker.compose.core.DockerCompose")
                || classPresent("org.testcontainers.containers.GenericContainer");
    }

    private boolean scheduledAvailable() {
        return beanPresent(ScheduledTaskHolder.class) || beanPresent(ScheduledAnnotationBeanPostProcessor.class);
    }

    private boolean aiAvailable() {
        return properties.getTelemetry().isEnabled() && classPresent("org.springframework.ai.chat.client.ChatClient");
    }

    private String aiUnavailableReason() {
        if (!properties.getTelemetry().isEnabled()) {
            return "Telemetry receiver is disabled";
        }
        return "Spring AI ChatClient is not on the classpath";
    }

    private boolean copilotAvailable() {
        if (properties.getCopilot().getEnabled() == BootUiProperties.Mode.OFF) {
            return false;
        }
        java.nio.file.Path dir = CopilotSessionStore.resolveDir(properties.getCopilot());
        return java.nio.file.Files.isDirectory(dir);
    }

    private String copilotUnavailableReason() {
        if (properties.getCopilot().getEnabled() == BootUiProperties.Mode.OFF) {
            return "Copilot panel is disabled via configuration";
        }
        return "Copilot CLI session-state directory not found at "
                + CopilotSessionStore.resolveDir(properties.getCopilot());
    }
}
