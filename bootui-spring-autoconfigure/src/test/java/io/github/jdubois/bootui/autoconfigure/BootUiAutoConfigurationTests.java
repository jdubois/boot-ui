package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jdubois.bootui.autoconfigure.activity.LiveActivityController;
import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.logging.SpringLoggerProvider;
import io.github.jdubois.bootui.autoconfigure.mcp.McpServerController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.pentesting.*;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.safety.LocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.safety.PanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.security.SecurityController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.*;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.engine.telemetry.BootUiSpanExporter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.boot.actuate.audit.listener.AuditListener;
import org.springframework.boot.actuate.autoconfigure.audit.AuditAutoConfiguration;
import org.springframework.boot.actuate.security.AuthenticationAuditListener;
import org.springframework.boot.actuate.security.AuthorizationAuditListener;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.InMemoryHttpExchangeRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.servlet.actuate.web.exchanges.HttpExchangesFilter;
import org.springframework.boot.servlet.filter.OrderedFilter;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.SpringProperties;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class BootUiAutoConfigurationTests {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class));

    @Test
    void doesNotActivateInAutoModeWithoutEnablingProfile() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(BootUiAutoConfiguration.class)
                .doesNotHaveBean(LocalhostOnlyFilter.class));
    }

    @Test
    void activatesWhenEnabledOn() {
        runner.withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(BootUiAutoConfiguration.class)
                        .hasSingleBean(LocalhostOnlyFilter.class)
                        .hasSingleBean(PanelAccessFilter.class)
                        .hasSingleBean(ConfigOverrideService.class)
                        .hasSingleBean(DevToolsBridge.class)
                        .hasSingleBean(DevToolsController.class)
                        .hasSingleBean(OverviewController.class)
                        .hasSingleBean(GitHubController.class)
                        .hasSingleBean(ConstellationController.class)
                        .hasSingleBean(DevServicesController.class)
                        .hasSingleBean(VulnerabilitiesController.class)
                        .hasSingleBean(PentestingController.class)
                        .hasSingleBean(AuditEventRepository.class)
                        .hasSingleBean(HttpExchangeRepository.class)
                        .hasSingleBean(HttpExchangesController.class)
                        .hasSingleBean(HttpSessionsController.class)
                        .hasSingleBean(BootUiSpanExporter.class)
                        .hasSingleBean(BootUiActivation.class));
    }

    @Test
    void activatesOnEnabledProfile() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasSingleBean(BootUiAutoConfiguration.class));
    }

    @Test
    void reportsEnabledInAotModeWhenRuntimeEnvironmentNoLongerMatches() {
        // In a native image the activation condition is frozen at AOT build time, so this bean only
        // exists because BootUI was enabled then. Simulate that with the AOT flag plus a bare
        // environment that resolve() would otherwise report as disabled (no enabling profile / devtools).
        SpringProperties.setProperty(AotDetector.AOT_ENABLED, "true");
        try {
            BootUiActivation activation = new BootUiAutoConfiguration().bootUiActivation(new MockEnvironment());
            assertThat(activation.enabled()).isTrue();
            assertThat(activation.reason()).contains("build time");
        } finally {
            SpringProperties.setProperty(AotDetector.AOT_ENABLED, null);
        }
    }

    @Test
    void reportsResolvedStateOutsideAotModeWhenEnvironmentDoesNotMatch() {
        BootUiActivation activation = new BootUiAutoConfiguration().bootUiActivation(new MockEnvironment());
        assertThat(activation.enabled()).isFalse();
    }

    @Test
    void disabledByProductionProfileEvenWithEnabledProfile() {
        runner.withPropertyValues("spring.profiles.active=prod,dev")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiAutoConfiguration.class));
    }

    @Test
    void enabledOnOverridesProductionProfile() {
        runner.withPropertyValues("spring.profiles.active=prod", "bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasSingleBean(BootUiAutoConfiguration.class);
                    BootUiActivation activation = context.getBean(BootUiActivation.class);
                    assertThat(activation.enabled()).isTrue();
                    assertThat(activation.warnings()).isNotEmpty();
                });
    }

    @Test
    void enabledOffForcesDisabledEvenWithDevProfile() {
        runner.withPropertyValues("spring.profiles.active=dev", "bootui.enabled=OFF")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiAutoConfiguration.class));
    }

    @Test
    void invalidEnabledValueFailsClosed() {
        runner.withPropertyValues("spring.profiles.active=dev", "bootui.enabled=maybe")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiAutoConfiguration.class));
    }

    @Test
    void bindsDefaultProperties() {
        runner.withPropertyValues("bootui.enabled=ON").run(context -> {
            BootUiProperties properties = context.getBean(BootUiProperties.class);
            assertThat(properties.getEnabled()).isEqualTo(BootUiProperties.Mode.ON);
            assertThat(properties.getPath()).isEqualTo("/bootui");
            assertThat(properties.getApiPath()).isEqualTo("/bootui/api");
            assertThat(properties.isAllowNonLocalhost()).isFalse();
            assertThat(properties.isMaskSecrets()).isTrue();
            assertThat(properties.getExposeValues()).isEqualTo(ValueExposure.MASKED);
            assertThat(properties.isShowBanner()).isTrue();
            assertThat(properties.getEnabledProfiles()).containsExactly("dev", "local");
            assertThat(properties.getDisabledProfiles()).containsExactly("prod", "production");
            assertThat(properties.getOverridesFile()).isEqualTo(".bootui/application-bootui.properties");
            assertThat(properties.getDevServices().isRestartEnabled()).isFalse();
            assertThat(properties.getDevServices().getLogTailBytes()).isEqualTo(64 * 1024);
            assertThat(properties.getCache().isClearEnabled()).isTrue();
            assertThat(properties.getHttpSessions().getMaxSessions()).isEqualTo(50);
            assertThat(properties.getCopilot().getMaxParsedSessions()).isEqualTo(100);
            assertThat(properties.getClaudeCode().getMaxParsedSessions()).isEqualTo(100);
        });
    }

    @Test
    void bindsCustomProperties() {
        runner.withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.path=/admin",
                        "bootui.api-path=/admin/api",
                        "bootui.mask-secrets=false",
                        "bootui.expose-values=FULL",
                        "bootui.read-only=true",
                        "bootui.overrides-file=/tmp/bootui.properties",
                        "bootui.panels.config.enabled=false",
                        "bootui.panels.loggers.read-only=true",
                        "bootui.dev-services.restart-enabled=true",
                        "bootui.dev-services.log-tail-bytes=2048",
                        "bootui.cache.clear-enabled=false",
                        "bootui.http-exchanges.max-exchanges=2",
                        "bootui.http-sessions.max-sessions=12",
                        "bootui.vulnerabilities.osv-enabled=false",
                        "bootui.vulnerabilities.max-packages=42",
                        "bootui.vulnerabilities.max-advisories=24",
                        "bootui.copilot.max-parsed-sessions=12",
                        "bootui.claude-code.max-parsed-sessions=8")
                .run(context -> {
                    BootUiProperties properties = context.getBean(BootUiProperties.class);
                    assertThat(properties.getPath()).isEqualTo("/admin");
                    assertThat(properties.getApiPath()).isEqualTo("/admin/api");
                    assertThat(properties.isMaskSecrets()).isFalse();
                    assertThat(properties.getExposeValues()).isEqualTo(ValueExposure.FULL);
                    assertThat(properties.isReadOnly()).isTrue();
                    assertThat(properties.getOverridesFile()).isEqualTo("/tmp/bootui.properties");
                    assertThat(properties.isPanelEnabled("config")).isFalse();
                    assertThat(properties.isPanelReadOnly("loggers")).isTrue();
                    assertThat(properties.getDevServices().isRestartEnabled()).isTrue();
                    assertThat(properties.getDevServices().getLogTailBytes()).isEqualTo(2048);
                    assertThat(properties.getCache().isClearEnabled()).isFalse();
                    assertThat(properties.getHttpExchanges().getMaxExchanges()).isEqualTo(2);
                    assertThat(properties.getHttpSessions().getMaxSessions()).isEqualTo(12);
                    assertThat(properties.getVulnerabilities().isOsvEnabled()).isFalse();
                    assertThat(properties.getVulnerabilities().getMaxPackages()).isEqualTo(42);
                    assertThat(properties.getVulnerabilities().getMaxAdvisories())
                            .isEqualTo(24);
                    assertThat(properties.getGithub().isApiEnabled()).isTrue();
                    assertThat(properties.getGithub().getMaxApiCalls()).isEqualTo(17);
                    assertThat(properties.getCopilot().getMaxParsedSessions()).isEqualTo(12);
                    assertThat(properties.getClaudeCode().getMaxParsedSessions())
                            .isEqualTo(8);
                });
    }

    @Test
    void requestDrivenBootUiBeansAreLazyWhileInfrastructureStaysEager() {
        runner.withPropertyValues("bootui.enabled=ON").run(context -> {
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();

            List.of(
                            AiController.class,
                            ArchitectureController.class,
                            RestApiController.class,
                            BeansController.class,
                            BootUiIndexController.class,
                            SpringCacheController.class,
                            ClaudeCodeController.class,
                            ConditionsController.class,
                            ConfigController.class,
                            CopilotController.class,
                            DataController.class,
                            FlywayController.class,
                            LiquibaseController.class,
                            VulnerabilitiesController.class,
                            DevToolsController.class,
                            GitHubController.class,
                            ConstellationController.class,
                            GraalVmController.class,
                            CracController.class,
                            LiveActivityController.class,
                            SqlTraceController.class,
                            HealthController.class,
                            DatabaseConnectionPoolsController.class,
                            HttpExchangesController.class,
                            HttpSessionsController.class,
                            HttpProbeController.class,
                            HeapDumpController.class,
                            LoggersController.class,
                            MappingsController.class,
                            LiveMemoryController.class,
                            JvmTuningController.class,
                            MetricsController.class,
                            OtlpReceiverController.class,
                            OverviewController.class,
                            PanelsController.class,
                            PentestingController.class,
                            ProfileDiffController.class,
                            ScheduledController.class,
                            SecurityLogsController.class,
                            SecurityController.class,
                            SpringController.class,
                            SpringSecurityController.class,
                            StartupController.class,
                            TracesController.class,
                            ThreadDumpController.class,
                            MemoryController.class,
                            McpServerController.class,
                            DismissedRulesController.class)
                    .forEach(beanType -> assertLazyBean(beanFactory, beanType));

            assertLazyBeanDefinition(beanFactory, "bootUiConfigOverrideService");
            assertLazyBeanDefinition(beanFactory, "bootUiDevToolsBridge");
            assertLazyBeanDefinition(beanFactory, "bootUiOtlpSpanDecoder");
            assertLazyBeanDefinition(beanFactory, "bootUiThreadDumpService");
            assertLazyBeanDefinition(beanFactory, "bootUiHeapDumpService");

            assertEagerBean(beanFactory, BootUiActivation.class);
            assertEagerBean(beanFactory, DevServicesController.class);
            assertEagerBean(beanFactory, LocalhostOnlyFilter.class);
            assertEagerBean(beanFactory, LogTailController.class);
            assertEagerBean(beanFactory, PanelAccessFilter.class);
        });
    }

    @Test
    void lazyControllersKeepTheirRequestMappingsRegistered() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        BootUiAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
                    String overviewBeanName = singleBeanName(beanFactory, OverviewController.class);

                    RequestMappingHandlerMapping handlerMapping = context.getBean(RequestMappingHandlerMapping.class);

                    assertThat(handlerMapping.getHandlerMethods().keySet())
                            .anySatisfy(mappingInfo ->
                                    assertThat(mappingInfo.getPatternValues()).contains("/bootui/api/overview"));
                    assertThat(beanFactory.containsSingleton(overviewBeanName)).isFalse();
                });
    }

    @Test
    void registersBootUiResourceHandlerByDefault() {
        webMvcRunner().withPropertyValues("bootui.enabled=ON").run(context -> {
            assertThat(context).hasSingleBean(BootUiStaticResourceConfigurer.class);
            assertThat(bootUiResourcePatterns(context)).contains("/bootui/**");
        });
    }

    @Test
    void servesBootUiAssetsEvenWhenDefaultResourceMappingsDisabled() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "spring.web.resources.add-mappings=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(BootUiStaticResourceConfigurer.class);
                    // Spring Boot drops its default "/**" handler, but BootUI still registers its own,
                    // so the SPA shell is actually served from the classpath rather than returning 404.
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(
                                    (WebApplicationContext) context.getSourceApplicationContext())
                            .build();
                    mvc.perform(get("/bootui/index.html"))
                            .andExpect(status().isOk())
                            .andExpect(content().string(containsString("bootui-test-index")));
                });
    }

    @Test
    void doesNotRegisterStaticResourceConfigurerWhenInactive() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BootUiStaticResourceConfigurer.class));
    }

    @Test
    void loggersPanelGatesClosedAndServesEmptyWhenLoggersEndpointClassAbsent() {
        // Simulates an Actuator-less application: the LoggersEndpoint type is removed from the classpath.
        // The always-active engine LoggersService must stay wired (B1) while the actuator-typed
        // SpringLoggerProvider is gated off, and the panels manifest must not fail to classload on the
        // (now neutralized) LoggersEndpoint reference in PanelsController (B2).
        webMvcRunner()
                .withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate.logging.LoggersEndpoint"))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).doesNotHaveBean(SpringLoggerProvider.class);
                    assertThat(context).hasSingleBean(LoggersService.class);

                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(
                                    (WebApplicationContext) context.getSourceApplicationContext())
                            .build();
                    // B2: the panels manifest renders and gates the loggers panel closed.
                    mvc.perform(get("/bootui/api/panels"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath(
                                    "$.panels[?(@.id=='loggers')].available", org.hamcrest.Matchers.hasItem(false)));
                    // B1: the loggers endpoint itself serves a stable empty report rather than failing.
                    mvc.perform(get("/bootui/api/loggers"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.loggers").isEmpty())
                            .andExpect(jsonPath("$.availableLevels").isEmpty());
                });
    }

    private static WebApplicationContextRunner webMvcRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        BootUiAutoConfiguration.class));
    }

    private static java.util.Set<String> bootUiResourcePatterns(
            org.springframework.context.ApplicationContext context) {
        SimpleUrlHandlerMapping mapping = (SimpleUrlHandlerMapping) context.getBean("resourceHandlerMapping");
        return mapping.getUrlMap().keySet();
    }

    @Test
    void lazyControllersCanBeInstantiated() {
        runner.withPropertyValues("bootui.enabled=ON").run(context -> {
            // Importing a controller with multiple constructors but no @Autowired marker leaves the
            // container unable to pick one, which only surfaces when the lazy bean is first used.
            assertThat(context.getBean(HeapDumpController.class)).isNotNull();
        });
    }

    @Test
    void copilotAndClaudeCodeSessionStoresAreLazy(@TempDir Path tempDir) {
        runner.withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.copilot.enabled=ON",
                        "bootui.copilot.session-state-dir=" + tempDir.resolve("copilot"),
                        "bootui.claude-code.enabled=ON",
                        "bootui.claude-code.session-state-dir=" + tempDir.resolve("claude"))
                .run(context -> {
                    var beanFactory = context.getBeanFactory();
                    assertThat(beanFactory
                                    .getBeanDefinition("bootUiCopilotSessionStore")
                                    .isLazyInit())
                            .isTrue();
                    assertThat(beanFactory
                                    .getBeanDefinition("bootUiClaudeCodeSessionStore")
                                    .isLazyInit())
                            .isTrue();
                    assertThat(beanFactory.containsSingleton("bootUiCopilotSessionStore"))
                            .isFalse();
                    assertThat(beanFactory.containsSingleton("bootUiClaudeCodeSessionStore"))
                            .isFalse();

                    context.getBean(CopilotController.class).dashboard();
                    assertThat(beanFactory.containsSingleton("bootUiCopilotSessionStore"))
                            .isTrue();
                    assertThat(beanFactory.containsSingleton("bootUiClaudeCodeSessionStore"))
                            .isFalse();

                    context.getBean(ClaudeCodeController.class).dashboard();
                    assertThat(beanFactory.containsSingleton("bootUiClaudeCodeSessionStore"))
                            .isTrue();
                });
    }

    @Test
    void auditAutoConfigurationCreatesListenersFromBootUiRepository() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class, AuditAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(AuditEventRepository.class)
                        .hasSingleBean(AuditListener.class)
                        .hasSingleBean(AuthenticationAuditListener.class)
                        .hasSingleBean(AuthorizationAuditListener.class));
    }

    @Test
    void auditEventRepositoryBacksOffWhenHostProvidesOne() {
        InMemoryAuditEventRepository hostRepository = new InMemoryAuditEventRepository();

        runner.withBean(AuditEventRepository.class, () -> hostRepository)
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasSingleBean(AuditEventRepository.class);
                    assertThat(context.getBean(AuditEventRepository.class)).isSameAs(hostRepository);
                    assertThat(context.getBeanNamesForType(AuditEventRepository.class))
                            .doesNotContain("bootUiAuditEventRepository");
                });
    }

    @Test
    void disabledSecurityLogsPanelDoesNotCreateAuditEventRepository() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.panels.security-logs.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AuditEventRepository.class));
    }

    @Test
    void missingSpringSecurityCoreDoesNotCreateAuditEventRepository() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class, AuditAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(
                        "org.springframework.security.authentication.event.AbstractAuthenticationEvent"))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).doesNotHaveBean(AuditEventRepository.class);
                });
    }

    @Test
    void disabledSpringBootAuditEventsDoesNotCreateAuditEventRepository() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class, AuditAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON", "management.auditevents.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(AuditEventRepository.class)
                        .doesNotHaveBean(AuditListener.class)
                        .doesNotHaveBean(AuthenticationAuditListener.class)
                        .doesNotHaveBean(AuthorizationAuditListener.class));
    }

    @Test
    void httpExchangeRepositoryUsesConfiguredCapacity() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.http-exchanges.max-exchanges=2")
                .run(context -> {
                    HttpExchangeRepository repository = context.getBean(HttpExchangeRepository.class);

                    repository.add(exchange("/one"));
                    repository.add(exchange("/two"));
                    repository.add(exchange("/three"));

                    assertThat(repository.findAll()).hasSize(2);
                    assertThat(repository.findAll())
                            .extracting(
                                    exchange -> exchange.getRequest().getUri().getPath())
                            .containsExactly("/three", "/two");
                });
    }

    @Test
    void httpExchangesFilterRunsBeforeSpringSecurityFilterOrder() {
        runner.withPropertyValues("bootui.enabled=ON").run(context -> {
            HttpExchangesFilter filter = context.getBean(HttpExchangesFilter.class);

            assertThat(filter.getOrder()).isEqualTo(OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER - 101);
        });
    }

    @Test
    void disabledSpringBootHttpExchangeRecordingDoesNotCreateFilter() {
        runner.withPropertyValues("bootui.enabled=ON", "management.httpexchanges.recording.enabled=false")
                .run(context -> assertThat(context)
                        .hasSingleBean(HttpExchangeRepository.class)
                        .doesNotHaveBean(HttpExchangesFilter.class));
    }

    @Test
    void disabledHttpExchangesPanelDoesNotCreateRecordingRepository() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.panels.http-exchanges.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(HttpExchangeRepository.class)
                        .doesNotHaveBean(HttpExchangesFilter.class));
    }

    @Test
    void backsOffWhenApplicationContributesItsOwnHttpExchangeRepositoryFromLaterAutoConfiguration() {
        runner.withConfiguration(AutoConfigurations.of(ApplicationHttpExchangeRepositoryAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HttpExchangeRepository.class);
                    assertThat(context.getBeanNamesForType(HttpExchangeRepository.class))
                            .containsExactly("applicationHttpExchangeRepository");
                    assertThat(context).hasSingleBean(HttpExchangesFilter.class);
                    assertThat(context.getBean(HttpExchangesFilter.class))
                            .extracting("repository")
                            .isSameAs(context.getBean(HttpExchangeRepository.class));
                });
    }

    @Test
    void optionalClasspathPanelsAreRegisteredWhenDependenciesArePresent() {
        runner.withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(SpringCacheController.class)
                        .hasSingleBean(DataController.class)
                        .hasSingleBean(DatabaseConnectionPoolsController.class)
                        .hasSingleBean(HttpSessionsController.class)
                        .hasSingleBean(LogTailController.class)
                        .hasSingleBean(ScheduledController.class)
                        .hasSingleBean(SecurityLogsController.class)
                        .hasSingleBean(SpringSecurityController.class));
    }

    @Test
    void skipsSpringDataPanelWhenSpringDataRepositoryMetadataIsMissing() {
        runner.withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("org.springframework.data.repository.core.support"))
                .run(context -> assertThat(context).doesNotHaveBean(DataController.class));
    }

    @Test
    void skipsHikariPanelWhenHikariCpIsMissing() {
        runner.withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("com.zaxxer.hikari.HikariDataSource"))
                .run(context -> assertThat(context).doesNotHaveBean(DatabaseConnectionPoolsController.class));
    }

    @Test
    void skipsHttpSessionsPanelWhenTomcatIsMissing() {
        runner.withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("org.springframework.boot.tomcat.TomcatWebServer"))
                .run(context -> assertThat(context).doesNotHaveBean(HttpSessionsController.class));
    }

    @Test
    void skipsBootUiSpanExporterWhenTelemetryIsDisabled() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.telemetry.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiSpanExporter.class));
    }

    @Test
    void skipsBootUiSpanExporterWhenTracesPanelIsDisabled() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.panels.traces.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiSpanExporter.class));
    }

    @Test
    void skipsBootUiSpanExporterWhenOpenTelemetrySdkIsMissing() {
        runner.withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("io.opentelemetry.sdk.trace.export"))
                .run(context -> assertThat(context).doesNotHaveBean("bootUiSpanExporter"));
    }

    @Test
    void registersSpanEnrichmentBeansWithTheExporter() {
        runner.withPropertyValues("bootui.enabled=ON").run(context -> {
            assertThat(context).hasBean("bootUiIdentitySpanProcessor");
            assertThat(context).hasBean("bootUiSpanEnricher");
            assertThat(context).hasBean("bootUiSpanEnricherInstaller");
        });
    }

    @Test
    void skipsSpanEnrichmentBeansWhenTelemetryIsDisabled() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.telemetry.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("bootUiIdentitySpanProcessor");
                    assertThat(context).doesNotHaveBean("bootUiSpanEnricher");
                    assertThat(context).doesNotHaveBean("bootUiSpanEnricherInstaller");
                });
    }

    @Test
    void skipsSpanEnrichmentBeansWhenTracesPanelIsDisabled() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.panels.traces.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("bootUiIdentitySpanProcessor");
                    assertThat(context).doesNotHaveBean("bootUiSpanEnricher");
                });
    }

    @Test
    void skipsLogTailPanelWhenLogbackIsMissing() {
        runner.withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("ch.qos.logback.classic"))
                .run(context -> assertThat(context).doesNotHaveBean(LogTailController.class));
    }

    @Test
    void skipsScheduledPanelWhenSchedulingInfrastructureIsMissing() {
        runner.withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("org.springframework.scheduling.config.ScheduledTaskHolder"))
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledController.class));
    }

    @Test
    void skipsSpringSecurityPanelWhenSpringSecurityWebIsMissing() {
        runner.withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("org.springframework.security.web.FilterChainProxy"))
                .run(context -> assertThat(context).doesNotHaveBean(SpringSecurityController.class));
    }

    private static void assertLazyBean(ConfigurableListableBeanFactory beanFactory, Class<?> beanType) {
        String beanName = singleBeanName(beanFactory, beanType);
        assertLazyBeanDefinition(beanFactory, beanName);
        assertThat(beanFactory.containsSingleton(beanName)).isFalse();
    }

    private static void assertEagerBean(ConfigurableListableBeanFactory beanFactory, Class<?> beanType) {
        String beanName = singleBeanName(beanFactory, beanType);
        assertThat(beanFactory.getBeanDefinition(beanName).isLazyInit()).isFalse();
        assertThat(beanFactory.containsSingleton(beanName)).isTrue();
    }

    private static void assertLazyBeanDefinition(ConfigurableListableBeanFactory beanFactory, String beanName) {
        assertThat(beanFactory.getBeanDefinition(beanName).isLazyInit()).isTrue();
        assertThat(beanFactory.containsSingleton(beanName)).isFalse();
    }

    private static String singleBeanName(ConfigurableListableBeanFactory beanFactory, Class<?> beanType) {
        String[] beanNames = beanFactory.getBeanNamesForType(beanType, false, false);
        assertThat(beanNames).hasSize(1);
        return beanNames[0];
    }

    private static HttpExchange exchange(String path) {
        return new HttpExchange(
                Instant.parse("2026-06-03T09:15:00Z"),
                new HttpExchange.Request(URI.create("http://localhost" + path), "127.0.0.1", "GET", Map.of()),
                new HttpExchange.Response(200, Map.of()),
                null,
                null,
                Duration.ofMillis(1));
    }

    @AutoConfiguration(after = BootUiAutoConfiguration.class)
    static class ApplicationHttpExchangeRepositoryAutoConfiguration {

        @Bean
        HttpExchangeRepository applicationHttpExchangeRepository() {
            return new InMemoryHttpExchangeRepository();
        }
    }
}
