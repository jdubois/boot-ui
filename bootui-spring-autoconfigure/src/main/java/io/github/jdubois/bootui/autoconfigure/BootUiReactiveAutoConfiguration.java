package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.autoconfigure.pentesting.PentestingController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiIndexController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiStaticResourceConfigurer;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactivePanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.web.*;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.actuate.autoconfigure.web.exchanges.HttpExchangesProperties;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
 * <p><strong>Not yet ported (need genuinely new reactive-native work, not mechanical reuse):</strong></p>
 *
 * <ul>
 *   <li><strong>Server-Sent Events panels</strong> &mdash; Live Activity, SQL Trace's {@code /stream}
 *       endpoint, Log Tail's {@code /stream} endpoint, Copilot, and Claude Code all stream over
 *       {@code org.springframework.web.servlet.mvc.method.annotation.SseEmitter}, a Spring MVC-only type
 *       with no WebFlux equivalent (WebFlux streams via {@code Flux<ServerSentEvent<T>>} return types
 *       instead). The MCP Server panel's tool catalog references these controllers too, so it is deferred
 *       alongside them. See {@code docs/WEBFLUX-SUPPORT.md} for the tracked follow-up.</li>
 *   <li><strong>Exceptions</strong> &mdash; captured via a {@code HandlerExceptionResolver}, an MVC-only
 *       extension point; needs a reactive {@code WebExceptionHandler} twin.</li>
 *   <li><strong>Spring Security advisor, Security Logs, and BootUI's own Security auto-configuration
 *       bypass</strong> &mdash; coupled to servlet Spring Security ({@code FilterChainProxy},
 *       {@code HttpSecurity}); needs a {@code ServerHttpSecurity}/{@code SecurityWebFilterChain} ruleset.</li>
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
            DismissedRulesController.class.getName());

    private static final Set<String> LAZY_BEAN_NAMES =
            Set.of("bootUiConfigOverrideService", "bootUiDevToolsBridge", "bootUiOtlpSpanDecoder");

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
