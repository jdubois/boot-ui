package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernateadvisor.HibernateAdvisorController;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.TelemetryStore;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
import io.github.jdubois.bootui.autoconfigure.pentest.*;
import io.github.jdubois.bootui.autoconfigure.restadvisor.RestApiAdvisorController;
import io.github.jdubois.bootui.autoconfigure.safety.LocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.safety.PanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorController;
import io.github.jdubois.bootui.autoconfigure.web.*;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.boot.actuate.autoconfigure.web.exchanges.HttpExchangesProperties;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.actuate.web.exchanges.HttpExchangesFilter;
import org.springframework.boot.servlet.filter.OrderedFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Main BootUI auto-configuration entry point.
 *
 * <p>Activates only when {@link BootUiActivationCondition} matches, when the app
 * is a servlet web application, and when Spring MVC is on the classpath.</p>
 */
@AutoConfiguration
@AutoConfigureBefore(
        name = {
            "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.audit.AuditAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.web.exchanges.HttpExchangesEndpointAutoConfiguration",
            "org.springframework.boot.servlet.autoconfigure.actuate.web.exchanges.ServletHttpExchangesAutoConfiguration"
        })
@Conditional(BootUiActivationCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@EnableConfigurationProperties(BootUiProperties.class)
@ImportRuntimeHints(BootUiRuntimeHints.class)
@Import({
    OverviewController.class,
    GitHubController.class,
    PanelsController.class,
    BeansController.class,
    ConditionsController.class,
    ConfigController.class,
    MappingsController.class,
    HealthController.class,
    LoggersController.class,
    StartupController.class,
    DataController.class,
    HibernateAdvisorController.class,
    FlywayController.class,
    LiquibaseController.class,
    HikariController.class,
    SpringCacheController.class,
    DevServicesController.class,
    DependenciesController.class,
    BootUiAutoConfiguration.HttpExchangeRepositoryConfiguration.class,
    BootUiAutoConfiguration.SecurityAuditRepositoryConfiguration.class,
    ScheduledController.class,
    HttpProbeService.class,
    HttpProbeController.class,
    PentestController.class,
    HeapDumpController.class,
    ArchitectureController.class,
    RestApiAdvisorController.class,
    LogTailController.class,
    HttpExchangesController.class,
    ProfileController.class,
    SpringSecurityController.class,
    SecurityAdvisorController.class,
    SecurityLogsController.class,
    MemoryController.class,
    MetricsController.class,
    HttpSessionsController.class,
    DevToolsController.class,
    TracesController.class,
    AiController.class,
    OtlpReceiverController.class,
    CopilotController.class,
    ClaudeCodeController.class,
    GraalVmController.class,
    ThreadDumpController.class,
    BootUiIndexController.class,
    BootUiOpenTelemetryConfiguration.class
})
public class BootUiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootUiAutoConfiguration.class);

    private static final Set<String> LAZY_CONTROLLER_CLASS_NAMES = Set.of(
            AiController.class.getName(),
            ArchitectureController.class.getName(),
            RestApiAdvisorController.class.getName(),
            BeansController.class.getName(),
            BootUiIndexController.class.getName(),
            HibernateAdvisorController.class.getName(),
            SpringCacheController.class.getName(),
            ClaudeCodeController.class.getName(),
            ConditionsController.class.getName(),
            ConfigController.class.getName(),
            CopilotController.class.getName(),
            DataController.class.getName(),
            FlywayController.class.getName(),
            LiquibaseController.class.getName(),
            DependenciesController.class.getName(),
            DevToolsController.class.getName(),
            GitHubController.class.getName(),
            GraalVmController.class.getName(),
            HealthController.class.getName(),
            HikariController.class.getName(),
            HttpExchangesController.class.getName(),
            HttpSessionsController.class.getName(),
            HttpProbeController.class.getName(),
            HeapDumpController.class.getName(),
            LoggersController.class.getName(),
            MappingsController.class.getName(),
            MemoryController.class.getName(),
            MetricsController.class.getName(),
            OtlpReceiverController.class.getName(),
            OverviewController.class.getName(),
            PanelsController.class.getName(),
            PentestController.class.getName(),
            ProfileController.class.getName(),
            ScheduledController.class.getName(),
            SecurityLogsController.class.getName(),
            SecurityAdvisorController.class.getName(),
            SpringSecurityController.class.getName(),
            StartupController.class.getName(),
            TracesController.class.getName(),
            ThreadDumpController.class.getName());

    private static final Set<String> LAZY_BEAN_NAMES =
            Set.of("bootUiConfigOverrideService", "bootUiDevToolsBridge", "bootUiOtlpSpanDecoder");

    @Bean
    static BeanFactoryPostProcessor bootUiLazyBeanPostProcessor() {
        return beanFactory -> {
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
                String beanClassName = beanDefinition.getBeanClassName();
                if (LAZY_BEAN_NAMES.contains(beanName)
                        || (beanClassName != null && LAZY_CONTROLLER_CLASS_NAMES.contains(beanClassName))) {
                    beanDefinition.setLazyInit(true);
                }
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository")
    @ConditionalOnProperty(prefix = "bootui.panels.http-exchanges", name = "enabled", matchIfMissing = true)
    @EnableConfigurationProperties(HttpExchangesProperties.class)
    static class HttpExchangeRepositoryConfiguration {

        private static final int HTTP_EXCHANGES_FILTER_ORDER = OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER - 101;

        @Bean
        @ConditionalOnMissingBean(HttpExchangeRepository.class)
        HttpExchangeRepository bootUiHttpExchangeRepository(BootUiProperties properties) {
            InMemoryHttpExchangeRepository repository = new InMemoryHttpExchangeRepository();
            repository.setCapacity(Math.max(1, properties.getHttpExchanges().getMaxExchanges()));
            repository.setReverse(true);
            return repository;
        }

        @Bean
        @ConditionalOnMissingBean(HttpExchangesFilter.class)
        @ConditionalOnProperty(
                prefix = "management.httpexchanges.recording",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        HttpExchangesFilter bootUiHttpExchangesFilter(
                HttpExchangeRepository repository, HttpExchangesProperties properties) {
            HttpExchangesFilter filter = new HttpExchangesFilter(
                    repository, properties.getRecording().getInclude());
            filter.setOrder(HTTP_EXCHANGES_FILTER_ORDER);
            return filter;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "org.springframework.boot.actuate.audit.AuditEventRepository",
                "org.springframework.security.authentication.event.AbstractAuthenticationEvent"
            })
    @ConditionalOnProperty(name = "management.auditevents.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "bootui.panels.security-logs", name = "enabled", matchIfMissing = true)
    static class SecurityAuditRepositoryConfiguration {

        @Bean
        @ConditionalOnMissingBean(AuditEventRepository.class)
        AuditEventRepository bootUiAuditEventRepository() {
            return new InMemoryAuditEventRepository();
        }
    }

    @Bean
    public BootUiActivation bootUiActivation(Environment environment) {
        BootUiActivation activation =
                BootUiActivationCondition.resolve(environment, getClass().getClassLoader());
        log.info("BootUI activation: {}", activation.reason());
        for (String warning : activation.warnings()) {
            log.warn("BootUI activation warning: {}", warning);
        }
        return activation;
    }

    @Bean
    public ConfigOverrideService bootUiConfigOverrideService(
            ConfigurableEnvironment environment, BootUiProperties properties, BootUiExposure exposure) {
        return new ConfigOverrideService(environment, properties, exposure);
    }

    @Bean
    public BootUiExposure bootUiExposure(Environment environment, BootUiProperties properties) {
        return new BootUiExposure(environment, properties);
    }

    @Bean
    public DevToolsBridge bootUiDevToolsBridge(ApplicationContext applicationContext) {
        return new DefaultDevToolsBridge(applicationContext);
    }

    @Bean
    public LocalhostOnlyFilter bootUiLocalhostOnlyFilter(BootUiProperties properties) {
        return new LocalhostOnlyFilter(properties);
    }

    @Bean
    public PanelAccessFilter bootUiPanelAccessFilter(BootUiProperties properties) {
        return new PanelAccessFilter(properties);
    }

    @Bean
    public TelemetryStore bootUiTelemetryStore(BootUiProperties properties) {
        return new TelemetryStore(properties.getTelemetry());
    }

    @Bean
    public BootUiSelfDataFilter bootUiSelfDataFilter(BootUiProperties properties) {
        return new BootUiSelfDataFilter(properties);
    }

    @Bean
    public OtlpSpanDecoder bootUiOtlpSpanDecoder(BootUiProperties properties) {
        return new OtlpSpanDecoder(properties.getTelemetry());
    }

    @Bean(destroyMethod = "stop")
    @Lazy
    public io.github.jdubois.bootui.autoconfigure.web.CopilotSessionStore bootUiCopilotSessionStore(
            BootUiProperties properties) {
        io.github.jdubois.bootui.autoconfigure.web.CopilotSessionStore store =
                new io.github.jdubois.bootui.autoconfigure.web.CopilotSessionStore(properties.getCopilot());
        if (properties.getCopilot().getEnabled() != BootUiProperties.Mode.OFF) {
            store.start();
        }
        return store;
    }

    @Bean(destroyMethod = "stop")
    @Lazy
    public io.github.jdubois.bootui.autoconfigure.web.ClaudeCodeSessionStore bootUiClaudeCodeSessionStore(
            BootUiProperties properties) {
        io.github.jdubois.bootui.autoconfigure.web.ClaudeCodeSessionStore store =
                new io.github.jdubois.bootui.autoconfigure.web.ClaudeCodeSessionStore(properties.getClaudeCode());
        if (properties.getClaudeCode().getEnabled() != BootUiProperties.Mode.OFF) {
            store.start();
        }
        return store;
    }

    @Bean
    public FilterRegistrationBean<LocalhostOnlyFilter> bootUiLocalhostOnlyFilterRegistration(
            LocalhostOnlyFilter filter, BootUiProperties properties) {
        FilterRegistrationBean<LocalhostOnlyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(properties.getPath() + "/*", properties.getApiPath() + "/*");
        registration.setOrder(Integer.MIN_VALUE);
        registration.setName("bootUiLocalhostOnlyFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<PanelAccessFilter> bootUiPanelAccessFilterRegistration(
            PanelAccessFilter filter, BootUiProperties properties) {
        FilterRegistrationBean<PanelAccessFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(properties.getApiPath() + "/*");
        registration.setOrder(Integer.MIN_VALUE + 1);
        registration.setName("bootUiPanelAccessFilter");
        return registration;
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> bootUiStartupBanner(
            BootUiProperties properties, Environment environment) {
        return event -> {
            properties.getPanels().keySet().stream()
                    .filter(panelId -> !BootUiPanels.ids().contains(panelId))
                    .forEach(panelId -> log.warn(
                            "Unknown BootUI panel id '{}' configured under bootui.panels; known ids are {}",
                            panelId,
                            BootUiPanels.ids()));
            if (!properties.isShowBanner()) {
                return;
            }
            String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
            String contextPath = environment.getProperty("server.servlet.context-path", "");
            String url = "http://localhost:" + port + contextPath + properties.getPath();
            log.info("BootUI is available at {}", url);
        };
    }
}
