package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.exceptions.BootUiExceptionLogAppender;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.autoconfigure.pentesting.PentestingController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveAgentSessionController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiExceptionHandler;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiIndexController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiStaticResourceConfigurer;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveClaudeCodeController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveCopilotController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveExceptionsController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLogTailController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactivePanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSqlTraceController;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceDataSourceBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.web.*;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.nio.file.Path;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.actuate.web.exchanges.HttpExchangesWebFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.web.util.DisconnectedClientHelper;

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
 *   <li><strong>Live Activity</strong> &mdash; aggregates several other signal sources including the
 *       servlet-only {@code ServletRequestHandledEvent}; deferred to a later increment.</li>
 *   <li><strong>MCP Server</strong> &mdash; {@code BootUiMcpController}/{@code McpServerController} are
 *       themselves framework-neutral, but {@code BootUiMcpTools}'s constructor is hard-wired to the
 *       servlet controller types ({@code ObjectProvider<ExceptionsController>},
 *       {@code ObjectProvider<SqlTraceController>}, etc.), so it cannot resolve the new
 *       {@code Reactive*Controller} beans above even now that they exist. Wiring the MCP beans here
 *       would either advertise tools that silently 404, or need {@code BootUiMcpTools} itself
 *       genericized (or a parallel reactive tools catalog); deferred pending that decision. See
 *       {@code docs/WEBFLUX-SUPPORT.md}.</li>
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
    ReactiveLogTailController.class,
    ReactiveCopilotController.class,
    ReactiveClaudeCodeController.class,
    ReactiveBootUiIndexController.class,
    BootUiEngineConfiguration.class,
    BootUiOpenTelemetryConfiguration.class
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
}
