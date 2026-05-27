package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.TelemetryStore;
import io.github.jdubois.bootui.autoconfigure.safety.LocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.web.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Main BootUI auto-configuration entry point.
 *
 * <p>Activates only when {@link BootUiActivationCondition} matches, when the app
 * is a servlet web application, and when Spring MVC is on the classpath.</p>
 */
@AutoConfiguration
@Conditional(BootUiActivationCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@EnableConfigurationProperties(BootUiProperties.class)
@Import({
    OverviewController.class,
    PanelsController.class,
    BeansController.class,
    ConditionsController.class,
    ConfigController.class,
    MappingsController.class,
    HealthController.class,
    LoggersController.class,
    StartupController.class,
    DataController.class,
    CacheController.class,
    DevServicesController.class,
    DependenciesController.class,
    ScheduledController.class,
    HttpProbeController.class,
    LogTailController.class,
    ProfileController.class,
    SecurityController.class,
    MemoryController.class,
    MetricsController.class,
    DevToolsController.class,
    TracesController.class,
    AiController.class,
    OtlpReceiverController.class,
    BootUiIndexController.class
})
public class BootUiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootUiAutoConfiguration.class);

    @Bean
    public BootUiActivation bootUiActivation(Environment environment) {
        BootUiActivation activation = BootUiActivationCondition.resolve(environment, getClass().getClassLoader());
        log.info("BootUI activation: {}", activation.reason());
        for (String warning : activation.warnings()) {
            log.warn("BootUI activation warning: {}", warning);
        }
        return activation;
    }

    @Bean
    public ConfigOverrideService bootUiConfigOverrideService(ConfigurableEnvironment environment,
                                                             BootUiProperties properties) {
        return new ConfigOverrideService(environment, properties);
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
    public TelemetryStore bootUiTelemetryStore(BootUiProperties properties) {
        return new TelemetryStore(properties.getTelemetry());
    }

    @Bean
    public OtlpSpanDecoder bootUiOtlpSpanDecoder(BootUiProperties properties) {
        return new OtlpSpanDecoder(properties.getTelemetry());
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
    public ApplicationListener<ApplicationReadyEvent> bootUiStartupBanner(BootUiProperties properties,
                                                                          Environment environment) {
        return event -> {
            if (!properties.isShowBanner()) {
                return;
            }
            String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
            String contextPath = environment.getProperty("server.servlet.context-path", "");
            String url = "http://localhost:" + port + contextPath + properties.getPath();
            log.info("BootUI is available at {}", url);
        };
    }
}
