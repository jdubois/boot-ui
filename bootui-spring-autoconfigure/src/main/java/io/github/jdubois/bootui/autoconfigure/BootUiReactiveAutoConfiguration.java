package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.exceptions.BootUiExceptionLogAppender;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.kafka.KafkaController;
import io.github.jdubois.bootui.autoconfigure.mail.BootUiMailSenderBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.mail.EmailController;
import io.github.jdubois.bootui.autoconfigure.mcp.BootUiMcpService;
import io.github.jdubois.bootui.autoconfigure.mcp.McpServerState;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.autoconfigure.pentesting.PentestingController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveActivitySignalFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveAgentSessionController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiExceptionHandler;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiIndexController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiMcpController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiMcpServerController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiMcpTools;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiStaticResourceConfigurer;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveClaudeCodeController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveCopilotController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveExceptionsController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveHttpExchangeTraceFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLiveActivityController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLogTailController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveOtelTraceIdProvider;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactivePanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityEventTraceRegistry;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityHeadersFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSqlTraceController;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceDataSourceBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.web.*;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
import org.springframework.boot.webflux.actuate.web.exchanges.HttpExchangesWebFilter;
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
import org.springframework.web.util.DisconnectedClientHelper;
import tools.jackson.databind.ObjectMapper;

/**
 * Reactive (WebFlux) sibling of {@link BootUiAutoConfiguration}: activates BootUI on a Spring Boot 4
 * app that runs over WebFlux/Netty (a {@code DispatcherHandler}-based reactive web application) instead
 * of Spring MVC/servlet.
 *
 * <p>Mutually exclusive in practice with the servlet {@link BootUiAutoConfiguration}: Spring Boot picks
 * exactly one {@code WebApplicationType} per running application (when both Spring MVC and WebFlux are
 * on the classpath, {@code WebApplicationType.deduceFromClasspath()} always resolves {@code SERVLET}),
 * so at most one of the two BootUI auto-configurations ever activates.</p>
 *
 * <p><strong>Panel surface (Phase 2 of the WebFlux port).</strong> Every controller imported here is a
 * framework-neutral {@code @RestController} that was already reusable as-is (it talks only to
 * {@code bootui-engine}/{@code bootui-core} and the stack-agnostic {@code org.springframework.http.*} /
 * Actuator types), so the bulk of the panel surface lights up unchanged from the servlet adapter. A
 * handful of small, stack-agnostic {@code @Bean} methods were duplicated from
 * {@link BootUiAutoConfiguration} itself (not from {@link BootUiEngineConfiguration}, which is imported
 * wholesale) because they carry no framework-specific dependency and several imported controllers need
 * them directly: {@link #bootUiTelemetryStore} / {@link #bootUiSelfDataFilter} / {@link #bootUiExposure}
 * / {@link #bootUiConfigOverrideService} / {@link #bootUiDismissedRulesStore} /
 * {@link #bootUiOtlpSpanDecoder} / {@link #bootUiDevToolsBridge}. Two genuinely new pieces of
 * stack-specific wiring were also added:</p>
 *
 * <ul>
 *   <li>{@link ReactiveHttpExchangeRepositoryConfiguration} mirrors
 *       {@link BootUiAutoConfiguration.HttpExchangeRepositoryConfiguration}, swapping the servlet-only
 *       {@code HttpExchangesFilter} for Spring Boot's reactive {@link HttpExchangesWebFilter} so
 *       {@link HttpExchangesController} (itself framework-neutral: it only depends on the shared
 *       {@link HttpExchangeRepository} Actuator interface) has exchanges to read.</li>
 *   <li>{@link #bootUiReactiveLazyBeanPostProcessor} mirrors {@link BootUiAutoConfiguration}'s own
 *       lazy-controller-bean pattern (matched by bean class name) plus its lazy-bean-name pattern (for
 *       the handful of duplicated beans above that do I/O or reflection at construction time) so
 *       rarely-visited panels are not eagerly constructed at context startup, matching the servlet
 *       adapter's resource profile.</li>
 * </ul>
 *
 * <p><strong>Server-Sent Events panels (Phase 3 of the WebFlux port).</strong> Live Activity aside (see
 * below), every panel that streams change notifications over SSE has a reactive twin here, rebuilt on
 * {@code Flux<ServerSentEvent<T>>} instead of the servlet-only {@code SseEmitter}:
 * {@link io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSqlTraceController},
 * {@link ReactiveExceptionsController} (paired with {@link ReactiveBootUiExceptionHandler} for capture),
 * {@link io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityLogsController},
 * {@link ReactiveLogTailController}, and the agent-activity pair
 * {@link ReactiveCopilotController}/{@link ReactiveClaudeCodeController} (sharing behavior via
 * {@link ReactiveAgentSessionController}, exactly as their servlet originals share
 * {@code AgentSessionController}). Each reactive controller reuses a coalesced-tick broadcaster,
 * {@code ReactiveBootUiChangeStream} (a {@code Sinks.Many}-backed sibling of the servlet
 * {@code BootUiChangeStream}), except Log Tail and the agent-activity pair, which stream actual payload
 * data (log lines; session/dashboard snapshots) rather than a bare tick, matching their servlet
 * originals. {@code SecurityLogsController} was reclassified during this phase: it depends only on
 * Actuator's {@code AuditEventRepository}/{@code AuditApplicationEvent} - not on the Spring Security
 * advisor ruleset - so it ports independently of the (still-deferred) Security advisor below.</p>
 *
 * <p><strong>Not yet ported (need genuinely new reactive-native work, not mechanical reuse):</strong></p>
 *
 * <ul>
 *   <li><strong>Spring Security advisor and BootUI's own Security auto-configuration bypass</strong>
 *       &mdash; coupled to servlet Spring Security ({@code FilterChainProxy}, {@code HttpSecurity});
 *       needs a {@code ServerHttpSecurity}/{@code SecurityWebFilterChain} ruleset. (Security *Logs* is
 *       ported - see above - only the advisor that analyzes security configuration is deferred.)</li>
 *   <li><strong>HTTP Sessions</strong> &mdash; {@code jakarta.servlet.http.HttpSession} /
 *       the container {@code Manager} SPI have no faithful reactive analog ({@code WebSession} is a
 *       different contract); reported {@code NOT_APPLICABLE}.</li>
 * </ul>
 *
 * <p>One known validation gap carried into Phase 5: {@code SpringPentestingObservationCollector} (wired
 * lazily via {@link BootUiEngineConfiguration#bootUiPentestingScanner}) reads
 * {@code RequestMappingInfoHandlerMapping}, an MVC-only type, via
 * {@code ApplicationContext.getBeanProvider(...)}. On this module's own test classpath
 * {@code spring-webmvc} is always present (both starters are optional dependencies of the same module),
 * so this cannot be exercised end-to-end here; it must be verified against the dedicated WebFlux-only
 * sample app (no {@code spring-boot-starter-web} at all) once that lands, and gated with
 * {@code @ConditionalOnClass} if it turns out to be unsafe on a genuinely MVC-free classpath.</p>
 */
@AutoConfiguration
@AutoConfigureBefore(
        name = {
            "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.audit.AuditAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.web.exchanges.HttpExchangesEndpointAutoConfiguration",
            "org.springframework.boot.webflux.autoconfigure.actuate.web.exchanges.WebFluxHttpExchangesAutoConfiguration"
        })
@Conditional(BootUiActivationCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
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
    HibernateController.class,
    FlywayController.class,
    LiquibaseController.class,
    DatabaseConnectionPoolsController.class,
    SpringCacheController.class,
    DevServicesController.class,
    VulnerabilitiesController.class,
    BootUiReactiveAutoConfiguration.ReactiveHttpExchangeRepositoryConfiguration.class,
    HttpExchangesController.class,
    ScheduledController.class,
    HttpProbeController.class,
    PentestingController.class,
    HeapDumpController.class,
    ArchitectureController.class,
    RestApiController.class,
    ProfileDiffController.class,
    SpringController.class,
    LiveMemoryController.class,
    JvmTuningController.class,
    MetricsController.class,
    DevToolsController.class,
    TracesController.class,
    AiController.class,
    OtlpReceiverController.class,
    GraalVmController.class,
    CracController.class,
    ThreadDumpController.class,
    MemoryController.class,
    DismissedRulesController.class,
    BootUiReactiveAutoConfiguration.ReactiveSecurityAuditRepositoryConfiguration.class,
    BootUiReactiveAutoConfiguration.ReactiveExceptionsConfiguration.class,
    ReactiveExceptionsController.class,
    ReactiveSqlTraceController.class,
    ReactiveSecurityLogsController.class,
    ReactiveLiveActivityController.class,
    EmailController.class,
    KafkaController.class,
    ReactiveLogTailController.class,
    ReactiveCopilotController.class,
    ReactiveClaudeCodeController.class,
    ReactiveBootUiIndexController.class,
    BootUiEngineConfiguration.class,
    BootUiOpenTelemetryConfiguration.class,
    BootUiReactiveAutoConfiguration.ReactiveOpenTelemetryCorrelationConfiguration.class
})
public class BootUiReactiveAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootUiReactiveAutoConfiguration.class);

    private static final Set<String> LAZY_CONTROLLER_CLASS_NAMES = Set.of(
            AiController.class.getName(),
            ArchitectureController.class.getName(),
            RestApiController.class.getName(),
            BeansController.class.getName(),
            HibernateController.class.getName(),
            SpringCacheController.class.getName(),
            ConditionsController.class.getName(),
            ConfigController.class.getName(),
            DataController.class.getName(),
            FlywayController.class.getName(),
            LiquibaseController.class.getName(),
            VulnerabilitiesController.class.getName(),
            DevToolsController.class.getName(),
            GitHubController.class.getName(),
            GraalVmController.class.getName(),
            CracController.class.getName(),
            HealthController.class.getName(),
            DatabaseConnectionPoolsController.class.getName(),
            HttpExchangesController.class.getName(),
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
            SpringController.class.getName(),
            StartupController.class.getName(),
            TracesController.class.getName(),
            ThreadDumpController.class.getName(),
            MemoryController.class.getName(),
            DismissedRulesController.class.getName(),
            ReactiveExceptionsController.class.getName(),
            ReactiveSqlTraceController.class.getName(),
            ReactiveSecurityLogsController.class.getName(),
            ReactiveLiveActivityController.class.getName(),
            ReactiveBootUiMcpController.class.getName(),
            ReactiveBootUiMcpServerController.class.getName(),
            EmailController.class.getName(),
            KafkaController.class.getName(),
            ReactiveCopilotController.class.getName(),
            ReactiveClaudeCodeController.class.getName());

    private static final Set<String> LAZY_BEAN_NAMES = Set.of(
            "bootUiConfigOverrideService",
            "bootUiDevToolsBridge",
            "bootUiOtlpSpanDecoder",
            "bootUiCopilotSessionStore",
            "bootUiClaudeCodeSessionStore");

    @Bean
    static BeanFactoryPostProcessor bootUiReactiveLazyBeanPostProcessor() {
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
    @ConditionalOnMissingBean(name = "bootUiReactiveMcpServerController")
    static class ReactiveMcpConfiguration {

        @Bean
        McpServerState bootUiReactiveMcpServerState(BootUiProperties properties) {
            return new McpServerState(properties.getMcp().getEnabled());
        }

        @Bean
        @Lazy
        ReactiveBootUiMcpTools bootUiReactiveMcpTools(
                ObjectProvider<OverviewController> overview,
                ObjectProvider<HealthController> health,
                ObjectProvider<ConfigController> config,
                ObjectProvider<BeansController> beans,
                ObjectProvider<MappingsController> mappings,
                ObjectProvider<ReactiveExceptionsController> exceptions,
                ObjectProvider<ReactiveLiveActivityController> liveActivity,
                ObjectProvider<ReactiveSecurityLogsController> securityLogs,
                ObjectProvider<ReactiveSqlTraceController> sqlTrace,
                ObjectProvider<TracesController> traces,
                ObjectProvider<ReactiveLogTailController> logTail,
                ObjectProvider<HttpExchangesController> httpExchanges,
                ObjectProvider<ArchitectureController> architecture,
                ObjectProvider<SpringController> spring,
                ObjectProvider<HibernateController> hibernate,
                ObjectProvider<MemoryController> memory,
                ObjectProvider<PentestingController> pentesting,
                ObjectProvider<RestApiController> restApi,
                ObjectProvider<GraalVmController> graalvm,
                ObjectProvider<CracController> crac) {
            return new ReactiveBootUiMcpTools(
                    overview,
                    health,
                    config,
                    beans,
                    mappings,
                    exceptions,
                    liveActivity,
                    securityLogs,
                    sqlTrace,
                    traces,
                    logTail,
                    httpExchanges,
                    architecture,
                    spring,
                    hibernate,
                    memory,
                    pentesting,
                    restApi,
                    graalvm,
                    crac);
        }

        @Bean
        @Lazy
        BootUiMcpService bootUiReactiveMcpService(
                ReactiveBootUiMcpTools tools, BootUiProperties properties, ObjectProvider<ObjectMapper> objectMapper) {
            String version = BootUiReactiveAutoConfiguration.class.getPackage().getImplementationVersion();
            return new BootUiMcpService(tools, properties, objectMapper.getIfAvailable(ObjectMapper::new), version);
        }

        @Bean
        @Lazy
        ReactiveBootUiMcpController bootUiReactiveMcpController(
                BootUiMcpService service,
                ReactiveBootUiMcpTools tools,
                McpServerState state,
                BootUiProperties properties) {
            return new ReactiveBootUiMcpController(service, tools, state, properties);
        }

        @Bean
        @Lazy
        ReactiveBootUiMcpServerController bootUiReactiveMcpServerController(
                McpServerState state, ReactiveBootUiMcpTools tools, BootUiProperties properties) {
            return new ReactiveBootUiMcpServerController(state, tools, properties);
        }
    }

    @Bean
    public BootUiActivation bootUiActivation(Environment environment) {
        BootUiActivation activation =
                BootUiActivationCondition.resolve(environment, getClass().getClassLoader());
        if (!activation.enabled() && AotDetector.useGeneratedArtifacts()) {
            // See BootUiAutoConfiguration.bootUiActivation for why the frozen build-time decision wins
            // in a native image: this bean only exists because the condition matched at build time.
            activation = new BootUiActivation(true, "Enabled at build time (AOT/native image)", activation.warnings());
        }
        log.info("BootUI activation: {}", activation.reason());
        for (String warning : activation.warnings()) {
            log.warn("BootUI activation warning: {}", warning);
        }
        return activation;
    }

    /**
     * Reactive twin of {@link BootUiAutoConfiguration#bootUiStartupBanner}: logs "BootUI is available
     * at ..." once the reactive web server has actually bound its port. Deliberately duplicates
     * {@link BootUiAutoConfiguration#buildStartupUrl} into {@link #buildStartupUrl(Environment,
     * BootUiProperties)} below instead of calling it directly - the method body itself is
     * framework-neutral, but merely referencing the servlet {@code BootUiAutoConfiguration} class would
     * force the JVM to load and verify it, which resolves the {@code jakarta.servlet.Filter}-typed
     * signatures of its other {@code @Bean} methods (e.g. {@code FilterRegistrationBean<...>}) and throws
     * {@code NoClassDefFoundError} on a WebFlux classpath with no servlet API at all. Same rationale as
     * the other small helpers this class already duplicates rather than imports (see the class Javadoc).
     */
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

    @Bean
    public ReactiveSecurityHeadersFilter bootUiReactiveSecurityHeadersFilter(BootUiProperties properties) {
        return new ReactiveSecurityHeadersFilter(properties);
    }

    @Bean
    public ReactiveLocalhostOnlyFilter bootUiReactiveLocalhostOnlyFilter(BootUiProperties properties) {
        // Same rationale as LocalhostOnlyFilter: builds its own ContainerGatewayDetector so it can
        // auto-trust the container default gateway per bootui.trust-container-gateway.
        return new ReactiveLocalhostOnlyFilter(properties);
    }

    @Bean
    public ReactivePanelAccessFilter bootUiReactivePanelAccessFilter(BootUiProperties properties) {
        return new ReactivePanelAccessFilter(properties);
    }

    @Bean
    public ReactiveActivitySignalFilter bootUiReactiveActivitySignalFilter(
            BootUiProperties properties, ObjectProvider<ReactiveLiveActivityController> liveActivityController) {
        return new ReactiveActivitySignalFilter(properties, liveActivityController);
    }

    @Bean
    public ReactiveBootUiStaticResourceConfigurer bootUiReactiveStaticResourceConfigurer(Environment environment) {
        return new ReactiveBootUiStaticResourceConfigurer(environment);
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiTelemetryStore}: a plain
     * {@code new TelemetryStore(...)} with no stack-specific dependency, needed by
     * {@link BootUiOpenTelemetryConfiguration} and the Traces/AI Usage/OTLP controllers. Deliberately
     * does not also duplicate {@code bootUiTelemetryStoreIdleReclaimable} &mdash; the idle-reclaim
     * feature needs a reactive {@code ConsoleActivityFilter}/{@code ConsoleActivityTracker} port that is
     * deferred; without it, span capture simply never suspends while idle (a resource-usage gap, not a
     * correctness one).
     */
    @Bean
    public TelemetryStore bootUiTelemetryStore(BootUiProperties properties) {
        return new TelemetryStore(new SpringTelemetrySettings(properties));
    }

    /** Duplicates {@link BootUiAutoConfiguration#bootUiSelfDataFilter}: no stack-specific dependency. */
    @Bean
    public BootUiSelfDataFilter bootUiSelfDataFilter(BootUiProperties properties) {
        return new BootUiSelfDataFilter(properties);
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiExposure}: no stack-specific dependency. Needed by
     * {@link BootUiEngineConfiguration#bootUiConfigService} and several of its
     * {@code @ConditionalOnClass}-gated nested backends (e.g. Database Connection Pools), and by
     * {@link HttpExchangesController} directly.
     */
    @Bean
    public BootUiExposure bootUiExposure(Environment environment, BootUiProperties properties) {
        return new BootUiExposure(environment, properties);
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiConfigOverrideService}: no stack-specific
     * dependency. Marked lazy like its servlet counterpart (see {@link #LAZY_BEAN_NAMES}) since it does
     * file I/O (reads the overrides properties file) at construction time via
     * {@code ConfigOverridesFileStore}, which should not happen unless the Configuration panel is
     * actually visited.
     */
    @Bean
    public ConfigOverrideService bootUiConfigOverrideService(
            ConfigurableEnvironment environment, BootUiProperties properties, BootUiExposure exposure) {
        return new ConfigOverrideService(environment, properties, exposure);
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiDismissedRulesStore}: no stack-specific dependency.
     * Needed by {@link DismissedRulesController} (advisor dismissals persisted to
     * {@code .bootui/boot-ui.yml}).
     */
    @Bean
    public DismissedRulesStore bootUiDismissedRulesStore(BootUiProperties properties) {
        String overridesFile = properties.getOverridesFile();
        Path parent = (overridesFile != null && !overridesFile.isBlank())
                ? Paths.get(overridesFile).getParent()
                : null;
        String dir = (parent != null) ? parent.toString() : ".bootui";
        return new DismissedRulesStore(Paths.get(dir, "boot-ui.yml"));
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiOtlpSpanDecoder}: no stack-specific dependency.
     * Needed by {@link OtlpReceiverController}.
     */
    @Bean
    public OtlpSpanDecoder bootUiOtlpSpanDecoder(BootUiProperties properties) {
        return new OtlpSpanDecoder(properties.getTelemetry());
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiDevToolsBridge}: {@link DefaultDevToolsBridge} only
     * needs {@link ApplicationContext} and reflects on {@code spring-boot-devtools} classes at call time,
     * so it is stack-agnostic (DevTools restart/LiveReload work identically under WebFlux). Needed by
     * {@link DevToolsController}.
     */
    @Bean
    public DevToolsBridge bootUiDevToolsBridge(ApplicationContext applicationContext) {
        return new DefaultDevToolsBridge(applicationContext);
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiSqlTraceRecorder}: no stack-specific dependency.
     * Needed by {@link io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSqlTraceController}.
     * Deliberately does not also duplicate {@code bootUiSqlTraceRecorderIdleReclaimable} - like
     * {@link #bootUiTelemetryStore}, the idle-reclaim bridge needs the still-deferred reactive
     * {@code ConsoleActivityFilter}/{@code ConsoleActivityTracker} port; without it, SQL capture simply
     * never suspends while idle (a resource-usage gap, not a correctness one).
     */
    @Bean
    public SqlTraceRecorder bootUiSqlTraceRecorder(BootUiProperties properties) {
        BootUiProperties.SqlTrace sqlTrace = properties.getSqlTrace();
        boolean enabled = sqlTrace.isEnabled() && properties.isPanelEnabled(BootUiPanels.SQL_TRACE);
        return new SqlTraceRecorder(
                enabled,
                sqlTrace.isRecording(),
                sqlTrace.isCaptureParameters(),
                sqlTrace.isCaptureCallSite(),
                sqlTrace.getMaxEntries(),
                sqlTrace.getSlowQueryThresholdMillis(),
                sqlTrace.getMaxSqlLength(),
                sqlTrace.getMaxParameterLength(),
                sqlTrace.getNPlusOneThreshold());
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiSqlTraceDataSourceBeanPostProcessor}: no
     * stack-specific dependency (wraps {@code javax.sql.DataSource} beans directly). Must stay eager
     * like its servlet counterpart - a {@link BeanPostProcessor} has to be instantiated before the
     * regular singletons it processes, so it is deliberately absent from {@link #LAZY_BEAN_NAMES}.
     */
    @Bean
    static SqlTraceDataSourceBeanPostProcessor bootUiSqlTraceDataSourceBeanPostProcessor(
            ObjectProvider<SqlTraceRecorder> recorderProvider) {
        return new SqlTraceDataSourceBeanPostProcessor(recorderProvider);
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiMailSenderBeanPostProcessor}: wraps
     * {@code JavaMailSender} beans directly, no stack-specific dependency. Guarded by
     * {@code @ConditionalOnClass} because {@code JavaMailSender} comes from the optional
     * {@code spring-boot-starter-mail} dependency.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
            name = "org.springframework.mail.javamail.JavaMailSender")
    static BootUiMailSenderBeanPostProcessor bootUiMailSenderBeanPostProcessor(
            ObjectProvider<io.github.jdubois.bootui.engine.email.EmailCaptureService> captureServiceProvider) {
        return new BootUiMailSenderBeanPostProcessor(captureServiceProvider);
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiCopilotSessionStore}: {@link CopilotSessionStore}
     * is a {@code WatchService}-based directory watcher with no HTTP coupling, so it is stack-agnostic.
     * Needed by {@link ReactiveCopilotController}. Marked lazy (see {@link #LAZY_BEAN_NAMES}) like its
     * servlet counterpart, since {@code store.start()} does real I/O (registers filesystem watches).
     */
    @Bean(destroyMethod = "stop")
    public CopilotSessionStore bootUiCopilotSessionStore(BootUiProperties properties) {
        CopilotSessionStore store = new CopilotSessionStore(properties.getCopilot());
        if (properties.getCopilot().getEnabled() != BootUiProperties.Mode.OFF) {
            store.start();
        }
        return store;
    }

    /**
     * Duplicates {@link BootUiAutoConfiguration#bootUiClaudeCodeSessionStore}: same rationale as
     * {@link #bootUiCopilotSessionStore}. Needed by {@link ReactiveClaudeCodeController}.
     */
    @Bean(destroyMethod = "stop")
    public ClaudeCodeSessionStore bootUiClaudeCodeSessionStore(BootUiProperties properties) {
        ClaudeCodeSessionStore store = new ClaudeCodeSessionStore(properties.getClaudeCode());
        if (properties.getClaudeCode().getEnabled() != BootUiProperties.Mode.OFF) {
            store.start();
        }
        return store;
    }

    /**
     * Reactive sibling of {@code BootUiAutoConfiguration.SecurityAuditRepositoryConfiguration}: same
     * framework-neutral fallback {@link AuditEventRepository} bean (Actuator's audit event capture is
     * itself stack-agnostic - events are published via {@code ApplicationEventPublisher}), needed by
     * {@link io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityLogsController}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "org.springframework.boot.actuate.audit.AuditEventRepository",
                "org.springframework.security.authentication.event.AbstractAuthenticationEvent"
            })
    @ConditionalOnProperty(name = "management.auditevents.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "bootui.panels.security-logs", name = "enabled", matchIfMissing = true)
    static class ReactiveSecurityAuditRepositoryConfiguration {

        @Bean
        @ConditionalOnMissingBean(AuditEventRepository.class)
        AuditEventRepository bootUiAuditEventRepository() {
            return new InMemoryAuditEventRepository();
        }
    }

    /**
     * Reactive sibling of {@code BootUiAutoConfiguration.ExceptionsConfiguration}: same
     * framework-neutral {@link ExceptionStore}, but pairs it with {@link ReactiveBootUiExceptionHandler}
     * (a {@code WebExceptionHandler}) instead of the servlet-only {@code BootUiExceptionHandlerResolver}
     * (a {@code HandlerExceptionResolver}). Needed by {@link ReactiveExceptionsController}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "bootui.panels.exceptions", name = "enabled", matchIfMissing = true)
    static class ReactiveExceptionsConfiguration {

        @Bean
        ExceptionStore bootUiExceptionStore(BootUiProperties properties, ApplicationContext applicationContext) {
            BootUiProperties.Exceptions config = properties.getExceptions();
            ExceptionStore store = new ExceptionStore(
                    config.getMaxGroups(),
                    config.getMaxOccurrencesPerGroup(),
                    config.getMaxStackFrames(),
                    DisconnectedClientHelper::isClientDisconnectedException);
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
        ReactiveBootUiExceptionHandler bootUiReactiveBootUiExceptionHandler(ExceptionStore store) {
            return new ReactiveBootUiExceptionHandler(store);
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
     * Reactive sibling of {@link BootUiAutoConfiguration.HttpExchangeRepositoryConfiguration}: same
     * framework-neutral {@link HttpExchangeRepository} bean (Actuator's stack-agnostic capture
     * interface, also read by the shared {@link HttpExchangesController}), but registers Spring Boot's
     * reactive {@link HttpExchangesWebFilter} instead of the servlet-only {@code HttpExchangesFilter}.
     * Unlike the servlet filter, {@code HttpExchangesWebFilter} beans self-register with
     * {@code WebFilter}'s natural ordering (Spring Boot's own {@code WebFluxHttpExchangesAutoConfiguration}
     * does not set an explicit order either, so this mirrors upstream behavior rather than inventing one).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository")
    @ConditionalOnProperty(prefix = "bootui.panels.http-exchanges", name = "enabled", matchIfMissing = true)
    @EnableConfigurationProperties(HttpExchangesProperties.class)
    static class ReactiveHttpExchangeRepositoryConfiguration {

        private static final String BOOTUI_HTTP_EXCHANGE_REPOSITORY_BEAN = "bootUiReactiveHttpExchangeRepository";

        @Bean(BOOTUI_HTTP_EXCHANGE_REPOSITORY_BEAN)
        @ConditionalOnMissingBean(HttpExchangeRepository.class)
        HttpExchangeRepository bootUiReactiveHttpExchangeRepository(BootUiProperties properties) {
            InMemoryHttpExchangeRepository repository = new InMemoryHttpExchangeRepository();
            repository.setCapacity(Math.max(1, properties.getHttpExchanges().getMaxExchanges()));
            repository.setReverse(true);
            return repository;
        }

        /**
         * Same rationale as {@code BootUiAutoConfiguration.HttpExchangeRepositoryConfiguration
         * #bootUiHttpExchangeRepositoryDeduplicator}: BootUI runs {@code @AutoConfigureBefore} the
         * standard reactive HTTP exchange auto-configuration, so {@link ConditionalOnMissingBean} on
         * {@link #bootUiReactiveHttpExchangeRepository} cannot detect a repository the application
         * contributes later. Reconciling as a {@link BeanFactoryPostProcessor} runs after every bean
         * definition is registered and before any bean is instantiated, so the application's repository
         * always wins.
         */
        @Bean
        static BeanFactoryPostProcessor bootUiReactiveHttpExchangeRepositoryDeduplicator() {
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
        @ConditionalOnMissingBean(HttpExchangesWebFilter.class)
        @ConditionalOnProperty(
                prefix = "management.httpexchanges.recording",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        HttpExchangesWebFilter bootUiHttpExchangesWebFilter(
                HttpExchangeRepository repository, HttpExchangesProperties properties) {
            return new HttpExchangesWebFilter(
                    repository, properties.getRecording().getInclude());
        }
    }

    /**
     * Reactive sibling of the trace-id correlation Quarkus's adapter supplies natively (see
     * {@code QuarkusOtelTraceIdProvider}): installs an OpenTelemetry-backed {@link TraceIdProvider} onto
     * the SQL Trace recorder, the reactive exception handler, the {@link HttpExchangesController} (via the
     * side-buffer {@link HttpExchangeTraceRegistry}), and the {@link ReactiveSecurityLogsController} (via
     * {@link ReactiveSecurityEventTraceRegistry}), so {@link ReactiveLiveActivityController} - which
     * already feeds all four signal sources to the shared engine {@code LiveActivityAssembler} - can
     * actually nest a request's SQL/exception/security signals under it.
     *
     * <p>WebFlux has no thread-per-request invariant for {@code SqlTraceRecorder}'s default MDC-based
     * {@link TraceIdProvider} to rely on (Reactor Netty's event-loop / {@code boundedElastic} scheduler
     * hops break thread-local propagation), so it must instead read the active OpenTelemetry span, whose
     * context survives those hops - exactly like the Quarkus adapter, and for the same reason. Gated on
     * the OpenTelemetry SDK being present, exactly like {@link BootUiOpenTelemetryConfiguration} and
     * Quarkus's own {@code Capability.OPENTELEMETRY_TRACER} gate - deliberately <em>not</em> also gated on
     * {@code bootui.telemetry.enabled} (that property governs BootUI's own span capture for the
     * Traces/AI Usage panels, a separate concern from reading the id of a span the application's own
     * tracing already started). When OpenTelemetry is absent, every capture point keeps its existing
     * behavior unchanged (MDC-based for SQL, header-derived for HTTP exchanges, none for
     * exceptions/security events).</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
    static class ReactiveOpenTelemetryCorrelationConfiguration {

        @Bean
        ReactiveOtelTraceIdProvider bootUiReactiveOtelTraceIdProvider() {
            return new ReactiveOtelTraceIdProvider();
        }

        @Bean
        HttpExchangeTraceRegistry bootUiHttpExchangeTraceRegistry(BootUiProperties properties) {
            return new HttpExchangeTraceRegistry(properties.getHttpExchanges().getMaxExchanges());
        }

        @Bean
        ReactiveSecurityEventTraceRegistry bootUiReactiveSecurityEventTraceRegistry(BootUiProperties properties) {
            return new ReactiveSecurityEventTraceRegistry(
                    properties.getSecurityLogs().getMaxLogs());
        }

        @Bean
        ReactiveHttpExchangeTraceFilter bootUiReactiveHttpExchangeTraceFilter(
                BootUiProperties properties,
                HttpExchangeTraceRegistry registry,
                ReactiveOtelTraceIdProvider traceIdProvider) {
            return new ReactiveHttpExchangeTraceFilter(properties, registry, traceIdProvider);
        }

        /**
         * Installs the OpenTelemetry-backed provider/registries onto the already-constructed capture
         * points once all singletons exist, mirroring
         * {@link BootUiOpenTelemetryConfiguration#bootUiSpanEnricherInstaller}. Each target bean is
         * optional (its owning panel may be disabled), so every installation step is a no-op
         * {@link ObjectProvider#ifAvailable} rather than a hard dependency.
         */
        @Bean
        SmartInitializingSingleton bootUiReactiveTraceCorrelationInstaller(
                ReactiveOtelTraceIdProvider traceIdProvider,
                HttpExchangeTraceRegistry httpExchangeTraceRegistry,
                ReactiveSecurityEventTraceRegistry securityEventTraceRegistry,
                ObjectProvider<SqlTraceRecorder> sqlTraceRecorders,
                ObjectProvider<RestClientTraceRecorder> restClientTraceRecorders,
                ObjectProvider<HttpExchangesController> httpExchangesControllers,
                ObjectProvider<ReactiveBootUiExceptionHandler> exceptionHandlers,
                ObjectProvider<ReactiveSecurityLogsController> securityLogsControllers,
                ObjectProvider<CacheActivityRecorder> cacheActivityRecorders,
                ObjectProvider<io.github.jdubois.bootui.engine.email.EmailCaptureService> emailCaptureServices) {
            return () -> {
                sqlTraceRecorders.ifAvailable(recorder -> recorder.setTraceIdProvider(traceIdProvider));
                restClientTraceRecorders.ifAvailable(recorder -> recorder.setTraceIdProvider(traceIdProvider));
                httpExchangesControllers.ifAvailable(
                        controller -> controller.setTraceRegistry(httpExchangeTraceRegistry));
                exceptionHandlers.ifAvailable(handler -> handler.setTraceIdProvider(traceIdProvider));
                emailCaptureServices.ifAvailable(service -> service.setTraceIdProvider(traceIdProvider));
                securityLogsControllers.ifAvailable(controller -> {
                    controller.setTraceIdProvider(traceIdProvider);
                    controller.setTraceRegistry(securityEventTraceRegistry);
                });
                cacheActivityRecorders.ifAvailable(recorder -> recorder.setTraceIdProvider(traceIdProvider));
            };
        }
    }
}
