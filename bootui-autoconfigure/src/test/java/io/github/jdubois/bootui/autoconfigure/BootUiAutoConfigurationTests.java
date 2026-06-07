package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.otlp.BootUiSpanExporter;
import io.github.jdubois.bootui.autoconfigure.pentest.*;
import io.github.jdubois.bootui.autoconfigure.restadvisor.RestApiAdvisorController;
import io.github.jdubois.bootui.autoconfigure.safety.LocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.safety.PanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.securityadvisor.SecurityAdvisorController;
import io.github.jdubois.bootui.autoconfigure.web.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.boot.actuate.audit.listener.AuditListener;
import org.springframework.boot.actuate.autoconfigure.audit.AuditAutoConfiguration;
import org.springframework.boot.actuate.security.AuthenticationAuditListener;
import org.springframework.boot.actuate.security.AuthorizationAuditListener;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.servlet.actuate.web.exchanges.HttpExchangesFilter;
import org.springframework.boot.servlet.filter.OrderedFilter;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
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
                        .hasSingleBean(DevServicesController.class)
                        .hasSingleBean(DependenciesController.class)
                        .hasSingleBean(PentestController.class)
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
            assertThat(properties.getExposeValues()).isEqualTo(BootUiProperties.ValueExposure.MASKED);
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
                        "bootui.dependencies.osv-enabled=false",
                        "bootui.dependencies.max-packages=42",
                        "bootui.dependencies.max-advisories=24",
                        "bootui.copilot.max-parsed-sessions=12",
                        "bootui.claude-code.max-parsed-sessions=8")
                .run(context -> {
                    BootUiProperties properties = context.getBean(BootUiProperties.class);
                    assertThat(properties.getPath()).isEqualTo("/admin");
                    assertThat(properties.getApiPath()).isEqualTo("/admin/api");
                    assertThat(properties.isMaskSecrets()).isFalse();
                    assertThat(properties.getExposeValues()).isEqualTo(BootUiProperties.ValueExposure.FULL);
                    assertThat(properties.isReadOnly()).isTrue();
                    assertThat(properties.getOverridesFile()).isEqualTo("/tmp/bootui.properties");
                    assertThat(properties.isPanelEnabled("config")).isFalse();
                    assertThat(properties.isPanelReadOnly("loggers")).isTrue();
                    assertThat(properties.getDevServices().isRestartEnabled()).isTrue();
                    assertThat(properties.getDevServices().getLogTailBytes()).isEqualTo(2048);
                    assertThat(properties.getCache().isClearEnabled()).isFalse();
                    assertThat(properties.getHttpExchanges().getMaxExchanges()).isEqualTo(2);
                    assertThat(properties.getHttpSessions().getMaxSessions()).isEqualTo(12);
                    assertThat(properties.getDependencies().isOsvEnabled()).isFalse();
                    assertThat(properties.getDependencies().getMaxPackages()).isEqualTo(42);
                    assertThat(properties.getDependencies().getMaxAdvisories()).isEqualTo(24);
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
                            RestApiAdvisorController.class,
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
                            DependenciesController.class,
                            DevToolsController.class,
                            GitHubController.class,
                            GraalVmController.class,
                            HealthController.class,
                            HikariController.class,
                            HttpExchangesController.class,
                            HttpSessionsController.class,
                            HttpProbeController.class,
                            HeapDumpController.class,
                            LoggersController.class,
                            MappingsController.class,
                            MemoryController.class,
                            MetricsController.class,
                            OtlpReceiverController.class,
                            OverviewController.class,
                            PanelsController.class,
                            PentestController.class,
                            ProfileController.class,
                            ScheduledController.class,
                            SecurityLogsController.class,
                            SecurityAdvisorController.class,
                            SpringSecurityController.class,
                            StartupController.class,
                            TracesController.class,
                            ThreadDumpController.class)
                    .forEach(beanType -> assertLazyBean(beanFactory, beanType));

            assertLazyBeanDefinition(beanFactory, "bootUiConfigOverrideService");
            assertLazyBeanDefinition(beanFactory, "bootUiDevToolsBridge");
            assertLazyBeanDefinition(beanFactory, "bootUiOtlpSpanDecoder");

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
    void optionalClasspathPanelsAreRegisteredWhenDependenciesArePresent() {
        runner.withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(SpringCacheController.class)
                        .hasSingleBean(DataController.class)
                        .hasSingleBean(HikariController.class)
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
                .run(context -> assertThat(context).doesNotHaveBean(HikariController.class));
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
}
