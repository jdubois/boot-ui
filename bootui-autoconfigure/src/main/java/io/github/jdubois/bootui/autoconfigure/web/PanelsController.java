package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels.Panel;
import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import java.util.regex.Pattern;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.startup.StartupEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
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
        return new PanelsReport(BootUiPanels.all().stream().map(this::panel).toList());
    }

    private PanelDto panel(Panel definition) {
        Availability availability = availability(definition.id());
        boolean readOnly = definition.actionCapable() && properties.isPanelReadOnly(definition.id());
        return new PanelDto(
                definition.id(),
                definition.title(),
                availability.available(),
                availability.available() ? null : availability.unavailableReason(),
                properties.isPanelEnabled(definition.id()),
                readOnly,
                readOnly ? properties.panelReadOnlyReason(definition.id()) : null);
    }

    private Availability availability(String id) {
        return switch (id) {
            case BootUiPanels.OVERVIEW,
                    BootUiPanels.MEMORY,
                    BootUiPanels.TUNING_ADVISOR,
                    BootUiPanels.CONFIG,
                    BootUiPanels.HTTP_PROBE,
                    BootUiPanels.PENTEST,
                    BootUiPanels.VULNERABILITIES -> available();
            case BootUiPanels.HEAP_DUMP ->
                availability(HeapDumpService.hotspotAvailable(), "Heap dumps are not supported on this JVM");
            case BootUiPanels.ARCHITECTURE -> availability(architectureAvailable(), architectureUnavailableReason());
            case BootUiPanels.GRAALVM -> availability(graalvmAvailable(), graalvmUnavailableReason());
            case BootUiPanels.HEALTH ->
                availability(beanPresent(HealthEndpoint.class), "Actuator health endpoint not available");
            case BootUiPanels.METRICS ->
                availability(
                        classPresent("io.micrometer.core.instrument.MeterRegistry")
                                && beanPresent(MetricsEndpoint.class),
                        "Actuator metrics endpoint or MeterRegistry not available");
            case BootUiPanels.STARTUP ->
                availability(beanPresent(StartupEndpoint.class), "Actuator startup endpoint not available");
            case BootUiPanels.SCHEDULED -> availability(scheduledAvailable(), "Scheduling is not enabled");
            case BootUiPanels.PROFILES ->
                availability(profilesAvailable(), "No active profiles or profile-specific config sources available");
            case BootUiPanels.LOGGERS ->
                availability(beanPresent(LoggersEndpoint.class), "Actuator loggers endpoint not available");
            case BootUiPanels.BEANS ->
                availability(beanPresent(BeansEndpoint.class), "Actuator beans endpoint not available");
            case BootUiPanels.CONDITIONS ->
                availability(beanPresent(ConditionsReportEndpoint.class), "Actuator conditions endpoint not available");
            case BootUiPanels.MAPPINGS ->
                availability(beanPresent(MappingsEndpoint.class), "Actuator mappings endpoint not available");
            case BootUiPanels.DATA ->
                availability(
                        classPresent("org.springframework.data.repository.Repository"),
                        "Spring Data not on the classpath");
            case BootUiPanels.DATABASE_CONNECTION_POOLS -> availability(hikariAvailable(), hikariUnavailableReason());
            case BootUiPanels.CACHE ->
                availability(beanPresent(CacheManager.class), "No CacheManager beans are available");
            case BootUiPanels.SECURITY ->
                availability(
                        classPresent("org.springframework.security.web.SecurityFilterChain"),
                        "Spring Security not on the classpath");
            case BootUiPanels.AI -> availability(aiAvailable(), aiUnavailableReason());
            case BootUiPanels.TRACES ->
                availability(properties.getTelemetry().isEnabled(), "Telemetry receiver is disabled");
            case BootUiPanels.LOG_TAIL ->
                availability(classPresent("ch.qos.logback.classic.Logger"), "Logback not on the classpath");
            case BootUiPanels.DEVTOOLS -> availability(devToolsPresent(), "Spring Boot DevTools not on the classpath");
            case BootUiPanels.DEV_SERVICES ->
                availability(devServicesPresent(), "Docker Compose or Testcontainers not on the classpath");
            case BootUiPanels.COPILOT ->
                availability(
                        cliSessionPanelAvailable(properties.getCopilot()),
                        cliSessionUnavailableReason(properties.getCopilot()));
            case BootUiPanels.CLAUDE_CODE ->
                availability(
                        cliSessionPanelAvailable(properties.getClaudeCode()),
                        cliSessionUnavailableReason(properties.getClaudeCode()));
            default -> availability(false, "Unknown BootUI panel");
        };
    }

    private Availability available() {
        return availability(true, null);
    }

    private Availability availability(boolean available, String unavailableReason) {
        return new Availability(available, unavailableReason);
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
        return properties.getTelemetry().isEnabled() && AiFrameworkDetector.isAnyPresent();
    }

    private boolean hikariAvailable() {
        return classPresent("com.zaxxer.hikari.HikariDataSource") && hikariDataSourceBeanPresent();
    }

    private boolean hikariDataSourceBeanPresent() {
        try {
            Class<?> type = ClassUtils.forName(
                    "com.zaxxer.hikari.HikariDataSource", getClass().getClassLoader());
            return beanPresent(type);
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private String hikariUnavailableReason() {
        if (!classPresent("com.zaxxer.hikari.HikariDataSource")) {
            return "No supported JDBC connection pool implementation is available";
        }
        return "No database connection pool beans are available";
    }

    private boolean architectureAvailable() {
        return classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")
                && AutoConfigurationPackages.has(applicationContext);
    }

    private String architectureUnavailableReason() {
        if (!classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")) {
            return "ArchUnit is not on the classpath";
        }
        return "No application base package was detected";
    }

    private boolean graalvmAvailable() {
        return classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")
                && AutoConfigurationPackages.has(applicationContext);
    }

    private String graalvmUnavailableReason() {
        if (!classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")) {
            return "ArchUnit is not on the classpath";
        }
        return "No application base package was detected";
    }

    private String aiUnavailableReason() {
        if (!properties.getTelemetry().isEnabled()) {
            return "Telemetry receiver is disabled";
        }
        return "Spring AI or LangChain4j is not on the classpath";
    }

    private boolean cliSessionPanelAvailable(BootUiProperties.Copilot settings) {
        if (settings.getEnabled() == BootUiProperties.Mode.OFF) {
            return false;
        }
        java.nio.file.Path dir = AgentSessionStore.resolveDir(settings);
        return java.nio.file.Files.isDirectory(dir);
    }

    private String cliSessionUnavailableReason(BootUiProperties.Copilot settings) {
        if (settings.getEnabled() == BootUiProperties.Mode.OFF) {
            return settings.getPanelTitle() + " panel is disabled via configuration";
        }
        return settings.getSessionSourceName() + " session directory not found at "
                + AgentSessionStore.resolveDir(settings);
    }

    private record Availability(boolean available, String unavailableReason) {}
}
