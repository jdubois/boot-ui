package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.activity.LiveActivityController;
import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationFilter;
import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry;
import io.github.jdubois.bootui.autoconfigure.activity.SecurityEventCorrelationRegistry;
import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.exceptions.BootUiExceptionHandlerResolver;
import io.github.jdubois.bootui.autoconfigure.exceptions.BootUiExceptionLogAppender;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionStore;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.idle.ConsoleActivityFilter;
import io.github.jdubois.bootui.autoconfigure.idle.ConsoleActivityTracker;
import io.github.jdubois.bootui.autoconfigure.idle.IdleReclaimable;
import io.github.jdubois.bootui.autoconfigure.mcp.BootUiMcpController;
import io.github.jdubois.bootui.autoconfigure.mcp.BootUiMcpService;
import io.github.jdubois.bootui.autoconfigure.mcp.BootUiMcpTools;
import io.github.jdubois.bootui.autoconfigure.mcp.McpServerController;
import io.github.jdubois.bootui.autoconfigure.mcp.McpServerState;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.autoconfigure.pentesting.*;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.safety.LocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.safety.PanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.security.SecurityController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceDataSourceBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRuntimeHints;
import io.github.jdubois.bootui.autoconfigure.web.*;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.nio.file.Paths;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.boot.actuate.autoconfigure.web.exchanges.HttpExchangesProperties;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
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
import tools.jackson.databind.ObjectMapper;

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
@ImportRuntimeHints({BootUiRuntimeHints.class, SqlTraceRuntimeHints.class})
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
    HibernateController.class,
    FlywayController.class,
    LiquibaseController.class,
    DatabaseConnectionPoolsController.class,
    SpringCacheController.class,
    DevServicesController.class,
    VulnerabilitiesController.class,
    BootUiAutoConfiguration.HttpExchangeRepositoryConfiguration.class,
    BootUiAutoConfiguration.SecurityAuditRepositoryConfiguration.class,
    ScheduledController.class,
    HttpProbeController.class,
    PentestingController.class,
    HeapDumpController.class,
    ArchitectureController.class,
    RestApiController.class,
    LogTailController.class,
    ExceptionsController.class,
    BootUiAutoConfiguration.ExceptionsConfiguration.class,
    BootUiAutoConfiguration.McpConfiguration.class,
    McpServerController.class,
    HttpExchangesController.class,
    ProfileDiffController.class,
    SpringSecurityController.class,
    SecurityController.class,
    SpringController.class,
    SecurityLogsController.class,
    LiveMemoryController.class,
    JvmTuningController.class,
    MetricsController.class,
    HttpSessionsController.class,
    DevToolsController.class,
    TracesController.class,
    AiController.class,
    OtlpReceiverController.class,
    CopilotController.class,
    ClaudeCodeController.class,
    GraalVmController.class,
    CracController.class,
    LiveActivityController.class,
    SqlTraceController.class,
    ThreadDumpController.class,
    MemoryController.class,
    DismissedRulesController.class,
    BootUiIndexController.class,
    BootUiEngineConfiguration.class,
    BootUiOpenTelemetryConfiguration.class
})
public class BootUiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootUiAutoConfiguration.class);

    private static final Set<String> LAZY_CONTROLLER_CLASS_NAMES = Set.of(
            AiController.class.getName(),
            ArchitectureController.class.getName(),
            RestApiController.class.getName(),
            BeansController.class.getName(),
            BootUiIndexController.class.getName(),
            HibernateController.class.getName(),
            SpringCacheController.class.getName(),
            ClaudeCodeController.class.getName(),
            ConditionsController.class.getName(),
            ConfigController.class.getName(),
            CopilotController.class.getName(),
            DataController.class.getName(),
            FlywayController.class.getName(),
            LiquibaseController.class.getName(),
            VulnerabilitiesController.class.getName(),
            DevToolsController.class.getName(),
            GitHubController.class.getName(),
            GraalVmController.class.getName(),
            CracController.class.getName(),
            LiveActivityController.class.getName(),
            SqlTraceController.class.getName(),
            HealthController.class.getName(),
            DatabaseConnectionPoolsController.class.getName(),
            HttpExchangesController.class.getName(),
            HttpSessionsController.class.getName(),
            HttpProbeController.class.getName(),
            HeapDumpController.class.getName(),
            LoggersController.class.getName(),
            MappingsController.class.getName(),
            LiveMemoryController.class.getName(),
            JvmTuningController.class.getName(),
            MetricsController.class.getName(),
            OtlpReceiverController.class.getName(),
            OverviewController.class.getName(),
            PanelsController.class.getName(),
            PentestingController.class.getName(),
            ProfileDiffController.class.getName(),
            ScheduledController.class.getName(),
            SecurityLogsController.class.getName(),
            SecurityController.class.getName(),
            SpringController.class.getName(),
            SpringSecurityController.class.getName(),
            StartupController.class.getName(),
            TracesController.class.getName(),
            ExceptionsController.class.getName(),
            ThreadDumpController.class.getName(),
            MemoryController.class.getName(),
            McpServerController.class.getName(),
            DismissedRulesController.class.getName());

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

        private static final String BOOTUI_HTTP_EXCHANGE_REPOSITORY_BEAN = "bootUiHttpExchangeRepository";

        @Bean
        @ConditionalOnMissingBean(HttpExchangeRepository.class)
        HttpExchangeRepository bootUiHttpExchangeRepository(BootUiProperties properties) {
            InMemoryHttpExchangeRepository repository = new InMemoryHttpExchangeRepository();
            repository.setCapacity(Math.max(1, properties.getHttpExchanges().getMaxExchanges()));
            repository.setReverse(true);
            return repository;
        }

        /**
         * Drops BootUI's fallback repository when the application also contributes its own
         * {@link HttpExchangeRepository}.
         *
         * <p>BootUI runs {@code @AutoConfigureBefore} the standard HTTP exchange auto-configurations,
         * so the {@link ConditionalOnMissingBean} guard on {@link #bootUiHttpExchangeRepository} cannot
         * detect a repository contributed by a configuration ordered after BootUI (such as the
         * application's own auto-configuration). That would otherwise leave two repositories in the
         * context and break single-bean injection &mdash; both BootUI's recording filter and Spring
         * Boot's own {@code httpExchangesEndpoint}. Reconciling as a {@link BeanFactoryPostProcessor}
         * runs after every bean definition has been registered, regardless of ordering, and before any
         * bean is instantiated, so the application's repository always wins.</p>
         */
        @Bean
        static BeanFactoryPostProcessor bootUiHttpExchangeRepositoryDeduplicator() {
            return beanFactory -> {
                if (!(beanFactory instanceof BeanDefinitionRegistry registry)
                        || !registry.containsBeanDefinition(BOOTUI_HTTP_EXCHANGE_REPOSITORY_BEAN)) {
                    return;
                }
                for (String name : beanFactory.getBeanNamesForType(HttpExchangeRepository.class, true, false)) {
                    if (!BOOTUI_HTTP_EXCHANGE_REPOSITORY_BEAN.equals(name)) {
                        registry.removeBeanDefinition(BOOTUI_HTTP_EXCHANGE_REPOSITORY_BEAN);
                        return;
                    }
                }
            };
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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "bootui.panels.exceptions", name = "enabled", matchIfMissing = true)
    static class ExceptionsConfiguration {

        @Bean
        ExceptionStore bootUiExceptionStore(BootUiProperties properties, ApplicationContext applicationContext) {
            BootUiProperties.Exceptions config = properties.getExceptions();
            ExceptionStore store = new ExceptionStore(
                    config.getMaxGroups(), config.getMaxOccurrencesPerGroup(), config.getMaxStackFrames());
            try {
                if (AutoConfigurationPackages.has(applicationContext)) {
                    store.setApplicationPackages(AutoConfigurationPackages.get(applicationContext));
                }
            } catch (RuntimeException ex) {
                log.debug("BootUI could not resolve application base packages for exception capture", ex);
            }
            return store;
        }

        @Bean
        BootUiExceptionHandlerResolver bootUiExceptionHandlerResolver(ExceptionStore store) {
            return new BootUiExceptionHandlerResolver(store);
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
        static class LogbackExceptionCaptureConfiguration {

            @Bean(destroyMethod = "uninstall")
            BootUiExceptionLogAppender bootUiExceptionLogAppender(ExceptionStore store) {
                return BootUiExceptionLogAppender.install(store);
            }
        }
    }

    /**
     * Registers the local-only MCP server beans. The beans are always registered while BootUI is
     * active, but the server only serves requests while {@link McpServerState} is enabled. The live
     * state is initialized from {@code bootui.mcp.enabled} (fail closed: {@code OFF}/{@code AUTO}
     * start disabled) and can be toggled at runtime from the MCP Server panel. BootUI's activation
     * condition keeps it confined to dev contexts, and the endpoint lives under {@code /bootui/api}
     * so it inherits the loopback/Host/cross-site safety filters. Tools are thin adapters over the
     * existing BootUI controllers, so masking and per-panel toggles still apply.
     */
    @Configuration(proxyBeanMethods = false)
    static class McpConfiguration {

        @Bean
        McpServerState bootUiMcpServerState(BootUiProperties properties) {
            return new McpServerState(properties.getMcp().getEnabled());
        }

        @Bean
        @Lazy
        BootUiMcpTools bootUiMcpTools(
                ObjectProvider<OverviewController> overview,
                ObjectProvider<HealthController> health,
                ObjectProvider<ConfigController> config,
                ObjectProvider<BeansController> beans,
                ObjectProvider<MappingsController> mappings,
                ObjectProvider<ExceptionsController> exceptions,
                ObjectProvider<SecurityLogsController> securityLogs,
                ObjectProvider<SqlTraceController> sqlTrace,
                ObjectProvider<TracesController> traces,
                ObjectProvider<LogTailController> logTail,
                ObjectProvider<HttpExchangesController> httpExchanges,
                ObjectProvider<ArchitectureController> architecture,
                ObjectProvider<SpringController> spring,
                ObjectProvider<HibernateController> hibernate,
                ObjectProvider<MemoryController> memory,
                ObjectProvider<SecurityController> security,
                ObjectProvider<PentestingController> pentesting,
                ObjectProvider<RestApiController> restApi,
                ObjectProvider<GraalVmController> graalvm,
                ObjectProvider<CracController> crac,
                BootUiProperties properties) {
            return new BootUiMcpTools(
                    overview,
                    health,
                    config,
                    beans,
                    mappings,
                    exceptions,
                    securityLogs,
                    sqlTrace,
                    traces,
                    logTail,
                    httpExchanges,
                    architecture,
                    spring,
                    hibernate,
                    memory,
                    security,
                    pentesting,
                    restApi,
                    graalvm,
                    crac,
                    Math.max(1, properties.getMcp().getMaxResults()));
        }

        @Bean
        @Lazy
        BootUiMcpService bootUiMcpService(
                BootUiMcpTools tools, BootUiProperties properties, ObjectProvider<ObjectMapper> objectMapper) {
            String version = BootUiAutoConfiguration.class.getPackage().getImplementationVersion();
            return new BootUiMcpService(tools, properties, objectMapper.getIfAvailable(ObjectMapper::new), version);
        }

        @Bean
        @Lazy
        BootUiMcpController bootUiMcpController(BootUiMcpService service, BootUiMcpTools tools, McpServerState state) {
            return new BootUiMcpController(service, tools, state);
        }
    }

    @Bean
    public BootUiActivation bootUiActivation(Environment environment) {
        BootUiActivation activation =
                BootUiActivationCondition.resolve(environment, getClass().getClassLoader());
        if (!activation.enabled() && AotDetector.useGeneratedArtifacts()) {
            // In a native image (or any AOT-generated context) BootUiAutoConfiguration's activation
            // condition is frozen from build time: this bean only exists because the condition
            // matched then. The live runtime environment may no longer match (for example the
            // build-time 'dev' profile is not active at runtime), so resolve() would mis-report
            // BootUI as disabled even though it is wired into the image and serving. Trust the frozen
            // build-time decision so the Overview panel and startup log reflect reality. A runtime
            // bootui.enabled=OFF cannot remove the baked-in beans, so it does not change this.
            activation = new BootUiActivation(true, "Enabled at build time (AOT/native image)", activation.warnings());
        }
        log.info("BootUI activation: {}", activation.reason());
        for (String warning : activation.warnings()) {
            log.warn("BootUI activation warning: {}", warning);
        }
        return activation;
    }

    @Bean
    public DismissedRulesStore bootUiDismissedRulesStore(BootUiProperties properties) {
        String overridesFile = properties.getOverridesFile();
        java.nio.file.Path parent = (overridesFile != null && !overridesFile.isBlank())
                ? Paths.get(overridesFile).getParent()
                : null;
        String dir = (parent != null) ? parent.toString() : ".bootui";
        return new DismissedRulesStore(Paths.get(dir, "boot-ui.yml"));
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
        // The filter constructs its own (Spring-free, package-private) ContainerGatewayDetector so it
        // can auto-trust the container default gateway per bootui.trust-container-gateway.
        return new LocalhostOnlyFilter(properties);
    }

    @Bean
    public BootUiStaticResourceConfigurer bootUiStaticResourceConfigurer(Environment environment) {
        return new BootUiStaticResourceConfigurer(environment);
    }

    @Bean
    public PanelAccessFilter bootUiPanelAccessFilter(BootUiProperties properties) {
        return new PanelAccessFilter(properties);
    }

    @Bean
    public TelemetryStore bootUiTelemetryStore(BootUiProperties properties) {
        return new TelemetryStore(new SpringTelemetrySettings(properties));
    }

    /**
     * Bridges the framework-neutral {@link TelemetryStore} to BootUI's idle-reclaim mechanism. The
     * store no longer implements {@link IdleReclaimable} (it stays engine-neutral), so this thin
     * delegate lets {@link ConsoleActivityTracker} suspend and resume span capture while the console
     * is idle, exactly as before the engine extraction.
     */
    @Bean
    public IdleReclaimable bootUiTelemetryStoreIdleReclaimable(TelemetryStore store) {
        return new IdleReclaimable() {
            @Override
            public void suspendForIdle() {
                store.suspendForIdle();
            }

            @Override
            public void resumeFromIdle() {
                store.resumeFromIdle();
            }
        };
    }

    @Bean
    public BootUiSelfDataFilter bootUiSelfDataFilter(BootUiProperties properties) {
        return new BootUiSelfDataFilter(properties);
    }

    @Bean
    public RequestCorrelationRegistry bootUiRequestCorrelationRegistry(BootUiProperties properties) {
        // This registry feeds exact thread-based correlation, not the display list, so it is sized well
        // above the page cap: a single page load records many static-resource requests, and undersizing
        // it would evict an API request's correlation before the feed or profiler reads it back.
        int capacity = Math.max(properties.getActivity().getMaxEntries() * 4, 512);
        return new RequestCorrelationRegistry(capacity);
    }

    @Bean
    public SecurityEventCorrelationRegistry bootUiSecurityEventCorrelationRegistry(BootUiProperties properties) {
        return new SecurityEventCorrelationRegistry(properties.getActivity().getMaxEntries());
    }

    @Bean
    public SqlTraceRecorder bootUiSqlTraceRecorder(BootUiProperties properties) {
        BootUiProperties.SqlTrace sqlTrace = properties.getSqlTrace();
        boolean enabled = sqlTrace.isEnabled() && properties.isPanelEnabled(BootUiPanels.SQL_TRACE);
        return new SqlTraceRecorder(
                enabled,
                sqlTrace.isRecording(),
                sqlTrace.isCaptureParameters(),
                sqlTrace.getMaxEntries(),
                sqlTrace.getSlowQueryThresholdMillis(),
                sqlTrace.getMaxSqlLength(),
                sqlTrace.getMaxParameterLength(),
                sqlTrace.getNPlusOneThreshold());
    }

    @Bean
    static SqlTraceDataSourceBeanPostProcessor bootUiSqlTraceDataSourceBeanPostProcessor(
            org.springframework.beans.factory.ObjectProvider<SqlTraceRecorder> recorderProvider) {
        return new SqlTraceDataSourceBeanPostProcessor(recorderProvider);
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

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "bootui.free-on-idle", name = "enabled", matchIfMissing = true)
    public ConsoleActivityTracker bootUiConsoleActivityTracker(
            BootUiProperties properties, ObjectProvider<IdleReclaimable> buffers) {
        return new ConsoleActivityTracker(
                properties.getFreeOnIdle().getTimeout(), buffers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnProperty(prefix = "bootui.free-on-idle", name = "enabled", matchIfMissing = true)
    public FilterRegistrationBean<ConsoleActivityFilter> bootUiConsoleActivityFilterRegistration(
            ConsoleActivityTracker tracker, BootUiProperties properties) {
        FilterRegistrationBean<ConsoleActivityFilter> registration =
                new FilterRegistrationBean<>(new ConsoleActivityFilter(properties, tracker));
        registration.addUrlPatterns(properties.getPath() + "/*", properties.getApiPath() + "/*");
        registration.setOrder(Integer.MIN_VALUE + 2);
        registration.setName("bootUiConsoleActivityFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestCorrelationFilter> bootUiRequestCorrelationFilterRegistration(
            RequestCorrelationRegistry registry, BootUiProperties properties) {
        FilterRegistrationBean<RequestCorrelationFilter> registration =
                new FilterRegistrationBean<>(new RequestCorrelationFilter(registry, properties.getPath()));
        registration.addUrlPatterns("/*");
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("bootUiRequestCorrelationFilter");
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
            log.info("BootUI is available at {}", buildStartupUrl(environment, properties));
        };
    }

    static String buildStartupUrl(Environment environment, BootUiProperties properties) {
        boolean sslEnabled = environment.getProperty("server.ssl.enabled", Boolean.class, false);
        String scheme = sslEnabled ? "https" : "http";
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        return scheme + "://localhost:" + port + contextPath + properties.getPath();
    }
}
