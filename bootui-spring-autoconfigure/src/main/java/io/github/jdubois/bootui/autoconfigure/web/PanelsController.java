package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import io.github.jdubois.bootui.engine.github.GitHubRepositoryDetector;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.panel.BootUiPanels.Panel;
import io.github.jdubois.bootui.engine.telemetry.AiFrameworkDetector;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpoint;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.startup.StartupEndpoint;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.core.NativeDetector;
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

    // Matches the application-<profile>.{properties,yml,yaml} token in both plain source names and
    // Spring Boot's "Config resource 'file ... application-<profile>.yml'" source names (via find()).
    private static final Pattern PROFILE_SOURCE_PATTERN =
            Pattern.compile("application-([\\w-]++)(?:\\.properties|\\.ya?ml)");

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
        return new PanelsReport(
                platform(), BootUiPanels.all().stream().map(this::panel).toList());
    }

    private String platform() {
        return isReactive() ? PanelsReport.PLATFORM_SPRING_BOOT_REACTIVE : PanelsReport.PLATFORM_SPRING_BOOT;
    }

    // This same controller class is imported unmodified by both BootUiAutoConfiguration (servlet) and
    // BootUiReactiveAutoConfiguration (WebFlux) - see the class-level shared-controller convention documented
    // in BootUiReactiveAutoConfiguration. ReactiveWebApplicationContext is the deterministic Spring Boot marker
    // for "this is the reactive stack" (set by the actual running ApplicationContext type, not a classpath
    // heuristic), so it correctly distinguishes the two adapters even if both spring-webmvc and spring-webflux
    // happen to be on the classpath at once.
    private boolean isReactive() {
        return applicationContext instanceof ReactiveWebApplicationContext;
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
        // Split across grouped helpers so no single switch grows unwieldy.
        // Each helper covers a disjoint set of panel ids; coreAvailability() returns null for ids it does
        // not own so the dispatcher can fall through to scannerAvailability(), which owns the default case.
        Availability availability = coreAvailability(id);
        return availability != null ? availability : scannerAvailability(id);
    }

    // Always-available panels plus those gated purely on an actuator endpoint, bean, or classpath marker.
    private Availability coreAvailability(String id) {
        return switch (id) {
            case BootUiPanels.OVERVIEW,
                    BootUiPanels.LIVE_MEMORY,
                    BootUiPanels.MEMORY,
                    BootUiPanels.CONFIG,
                    BootUiPanels.EXCEPTIONS,
                    BootUiPanels.HTTP_PROBE,
                    BootUiPanels.PENTESTING,
                    BootUiPanels.SPRING,
                    BootUiPanels.VULNERABILITIES,
                    BootUiPanels.ACTIVITY -> available();
            case BootUiPanels.MCP_SERVER -> availability(mcpServerAvailable(), mcpServerUnavailableReason());
            case BootUiPanels.JVM_TUNING ->
                availability(
                        !nativeImageDetected(), "JVM Tuning is not applicable when running as a GraalVM native image");
            case BootUiPanels.HEALTH ->
                availability(
                        beanPresent("org.springframework.boot.health.actuate.endpoint.HealthEndpoint"),
                        "Actuator health endpoint not available");
            case BootUiPanels.HTTP_SESSIONS -> availability(httpSessionsAvailable(), httpSessionsUnavailableReason());
            case BootUiPanels.METRICS ->
                availability(
                        classPresent("io.micrometer.core.instrument.MeterRegistry")
                                && beanPresent(MetricsEndpoint.class),
                        "Actuator metrics endpoint or MeterRegistry not available");
            case BootUiPanels.STARTUP ->
                availability(beanPresent(StartupEndpoint.class), "Actuator startup endpoint not available");
            case BootUiPanels.SCHEDULED -> availability(scheduledAvailable(), "Scheduling is not enabled");
            case BootUiPanels.PROFILE_DIFF ->
                availability(profilesAvailable(), "No active profiles or profile-specific config sources available");
            case BootUiPanels.LOGGERS ->
                availability(
                        beanPresent("org.springframework.boot.actuate.logging.LoggersEndpoint"),
                        "Actuator loggers endpoint not available");
            case BootUiPanels.BEANS ->
                availability(beanPresent(BeansEndpoint.class), "Actuator beans endpoint not available");
            case BootUiPanels.CONDITIONS ->
                availability(beanPresent(ConditionsReportEndpoint.class), "Actuator conditions endpoint not available");
            case BootUiPanels.MAPPINGS ->
                availability(
                        beanPresent("org.springframework.boot.actuate.web.mappings.MappingsEndpoint"),
                        "Actuator mappings endpoint not available");
            case BootUiPanels.DATA ->
                availability(
                        classPresent("org.springframework.data.repository.Repository"),
                        "Spring Data not on the classpath");
            case BootUiPanels.CACHE ->
                availability(beanPresent(CacheManager.class), "No CacheManager beans are available");
            case BootUiPanels.SPRING_SECURITY ->
                availability(springSecurityAvailable(), springSecurityUnavailableReason());
            case BootUiPanels.SECURITY_LOGS ->
                availability(beanPresent(AuditEventRepository.class), "No AuditEventRepository bean is available");
            case BootUiPanels.HTTP_EXCHANGES ->
                availability(
                        classPresent("org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository")
                                && beanPresent(HttpExchangeRepository.class),
                        "HTTP exchange repository not available");
            case BootUiPanels.TRACES ->
                availability(properties.getTelemetry().isEnabled(), "Telemetry receiver is disabled");
            case BootUiPanels.LOG_TAIL ->
                availability(classPresent("ch.qos.logback.classic.Logger"), "Logback not on the classpath");
            case BootUiPanels.DEVTOOLS -> availability(devToolsPresent(), "Spring Boot DevTools not on the classpath");
            case BootUiPanels.DEV_SERVICES ->
                availability(devServicesPresent(), "Docker Compose or Testcontainers not on the classpath");
            default -> null;
        };
    }

    // Panels backed by a dedicated scanner/service availability check, plus the unknown-panel fallback.
    private Availability scannerAvailability(String id) {
        return switch (id) {
            case BootUiPanels.GITHUB -> availability(githubAvailable(), githubUnavailableReason());
            case BootUiPanels.HEAP_DUMP ->
                availability(HeapDumpService.hotspotAvailable(), "Heap dumps are not supported on this JVM");
            case BootUiPanels.ARCHITECTURE -> availability(architectureAvailable(), architectureUnavailableReason());
            case BootUiPanels.REST_API -> availability(restApiAvailable(), restApiUnavailableReason());
            case BootUiPanels.GRAALVM -> availability(graalvmAvailable(), graalvmUnavailableReason());
            case BootUiPanels.CRAC -> availability(cracAvailable(), cracUnavailableReason());
            case BootUiPanels.SQL_TRACE ->
                availability(beanPresent(javax.sql.DataSource.class), "No DataSource bean is available");
            case BootUiPanels.THREADS ->
                availability(
                        java.lang.management.ManagementFactory.getThreadMXBean() != null,
                        "ThreadMXBean is not available on this JVM");
            case BootUiPanels.HIBERNATE -> availability(hibernateAvailable(), hibernateUnavailableReason());
            case BootUiPanels.DATABASE_CONNECTION_POOLS -> availability(hikariAvailable(), hikariUnavailableReason());
            case BootUiPanels.FLYWAY -> availability(flywayAvailable(), flywayUnavailableReason());
            case BootUiPanels.LIQUIBASE -> availability(liquibaseAvailable(), liquibaseUnavailableReason());
            case BootUiPanels.EMAIL -> availability(emailAvailable(), "No JavaMailSender bean is available");
            case BootUiPanels.SECURITY -> availability(securityAvailable(), securityUnavailableReason());
            case BootUiPanels.AI -> availability(aiAvailable(), aiUnavailableReason());
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

    private boolean githubAvailable() {
        return GitHubRepositoryDetector.detect(
                        Path.of(System.getProperty("user.dir", ".")),
                        Arrays.asList(properties.getGithub().getAllowedApiHosts()))
                .isPresent();
    }

    private String githubUnavailableReason() {
        return GitHubRepositoryDetector.unavailableReason(
                Path.of(System.getProperty("user.dir", ".")),
                Arrays.asList(properties.getGithub().getAllowedApiHosts()));
    }

    private boolean aiAvailable() {
        return properties.getTelemetry().isEnabled() && AiFrameworkDetector.isAnyPresent();
    }

    private boolean httpSessionsAvailable() {
        return classPresent("org.springframework.boot.tomcat.TomcatWebServer")
                && classPresent("org.apache.catalina.Manager")
                && applicationContext instanceof WebServerApplicationContext webServerApplicationContext
                && webServerApplicationContext.getWebServer() != null
                && "org.springframework.boot.tomcat.TomcatWebServer"
                        .equals(webServerApplicationContext
                                .getWebServer()
                                .getClass()
                                .getName());
    }

    private String httpSessionsUnavailableReason() {
        if (isReactive()) {
            return "Not applicable on Spring WebFlux: HTTP Sessions are the servlet container's HttpSession API,"
                    + " which has no reactive equivalent (WebSession is a different, non-container-managed model),"
                    + " so this panel does not apply here.";
        }
        if (!classPresent("org.springframework.boot.tomcat.TomcatWebServer")
                || !classPresent("org.apache.catalina.Manager")) {
            return "HTTP Sessions require embedded Tomcat";
        }
        if (!(applicationContext instanceof WebServerApplicationContext)) {
            return "HTTP Sessions require an embedded servlet web server";
        }
        return "HTTP Sessions require embedded Tomcat";
    }

    private boolean springSecurityAvailable() {
        return !isReactive()
                && classPresent("org.springframework.security.web.SecurityFilterChain")
                && beanPresent("org.springframework.security.web.SecurityFilterChain");
    }

    private String springSecurityUnavailableReason() {
        if (isReactive()) {
            return "Not yet ported for Spring WebFlux: this advisor analyzes the servlet"
                    + " SecurityFilterChain/HttpSecurity configuration model, which has no reactive equivalent"
                    + " wired here yet (a ServerHttpSecurity/SecurityWebFilterChain ruleset is planned).";
        }
        if (!classPresent("org.springframework.security.web.SecurityFilterChain")) {
            return "Spring Security not on the classpath";
        }
        return "No Spring Security filter chains are available";
    }

    private boolean mcpServerAvailable() {
        return !isReactive();
    }

    private String mcpServerUnavailableReason() {
        return "Not yet ported for Spring WebFlux: the MCP tool catalog is hard-wired to the servlet panel"
                + " controllers, so it cannot yet resolve the reactive panel surface.";
    }

    private boolean hikariAvailable() {
        return classPresent("com.zaxxer.hikari.HikariDataSource")
                && HikariDataSourceDiscovery.hasAny(applicationContext);
    }

    private String hikariUnavailableReason() {
        if (!classPresent("com.zaxxer.hikari.HikariDataSource")) {
            return "No supported JDBC connection pool implementation is available";
        }
        return "No database connection pool beans are available";
    }

    private boolean flywayAvailable() {
        return classPresent("org.flywaydb.core.Flyway") && beanPresent("org.flywaydb.core.Flyway");
    }

    private String flywayUnavailableReason() {
        if (!classPresent("org.flywaydb.core.Flyway")) {
            return "Flyway is not on the classpath";
        }
        return "No Flyway beans are available";
    }

    private boolean liquibaseAvailable() {
        return classPresent("liquibase.integration.spring.SpringLiquibase")
                && beanPresent("liquibase.integration.spring.SpringLiquibase");
    }

    private String liquibaseUnavailableReason() {
        if (!classPresent("liquibase.integration.spring.SpringLiquibase")) {
            return "Liquibase is not on the classpath";
        }
        return "No Liquibase beans are available";
    }

    private boolean emailAvailable() {
        return classPresent("org.springframework.mail.javamail.JavaMailSender")
                && beanPresent("org.springframework.mail.javamail.JavaMailSender");
    }

    private boolean beanPresent(String className) {
        try {
            Class<?> type = ClassUtils.forName(className, getClass().getClassLoader());
            return beanPresent(type);
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private boolean hibernateAvailable() {
        return classPresent("org.hibernate.SessionFactory")
                && classPresent("jakarta.persistence.EntityManagerFactory")
                && beanPresent("jakarta.persistence.EntityManagerFactory");
    }

    private String hibernateUnavailableReason() {
        if (!classPresent("org.hibernate.SessionFactory")) {
            return "Hibernate ORM is not on the classpath";
        }
        if (!classPresent("jakarta.persistence.EntityManagerFactory")) {
            return "Jakarta Persistence is not on the classpath";
        }
        return "No EntityManagerFactory beans are available";
    }

    private boolean securityAvailable() {
        return classPresent("org.springframework.security.web.FilterChainProxy")
                && beanPresent("org.springframework.security.web.FilterChainProxy");
    }

    private String securityUnavailableReason() {
        if (!classPresent("org.springframework.security.web.FilterChainProxy")) {
            return "Spring Security not on the classpath";
        }
        return "No Spring Security filter chains are available";
    }

    private boolean architectureAvailable() {
        return !nativeImageDetected()
                && classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")
                && AutoConfigurationPackages.has(applicationContext);
    }

    private String architectureUnavailableReason() {
        if (nativeImageDetected()) {
            return "Architecture advisor is not applicable when running as a GraalVM native image";
        }
        if (!classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")) {
            return "ArchUnit is not on the classpath";
        }
        return "No application base package was detected";
    }

    private boolean restApiAvailable() {
        return !nativeImageDetected()
                && classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")
                && AutoConfigurationPackages.has(applicationContext);
    }

    private String restApiUnavailableReason() {
        if (nativeImageDetected()) {
            return "REST API advisor is not applicable when running as a GraalVM native image";
        }
        if (!classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")) {
            return "ArchUnit is not on the classpath";
        }
        return "No application base package was detected";
    }

    private boolean graalvmAvailable() {
        return !nativeImageDetected()
                && classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")
                && AutoConfigurationPackages.has(applicationContext);
    }

    private String graalvmUnavailableReason() {
        if (nativeImageDetected()) {
            return "GraalVM readiness advisor is not applicable when already running as a native image";
        }
        if (!classPresent("com.tngtech.archunit.core.importer.ClassFileImporter")) {
            return "ArchUnit is not on the classpath";
        }
        return "No application base package was detected";
    }

    private boolean cracAvailable() {
        return !nativeImageDetected();
    }

    private String cracUnavailableReason() {
        return "CRaC is not applicable when running as a GraalVM native image";
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

    boolean nativeImageDetected() {
        return NativeDetector.inNativeImage();
    }

    private record Availability(boolean available, String unavailableReason) {}
}
