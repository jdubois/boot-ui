package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.otlp.BootUiSpanExporter;
import io.github.jdubois.bootui.autoconfigure.pentest.*;
import io.github.jdubois.bootui.autoconfigure.safety.LocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.safety.PanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.web.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
                        .hasSingleBean(DevServicesController.class)
                        .hasSingleBean(DependenciesController.class)
                        .hasSingleBean(PentestController.class)
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
                    assertThat(properties.getDependencies().isOsvEnabled()).isFalse();
                    assertThat(properties.getDependencies().getMaxPackages()).isEqualTo(42);
                    assertThat(properties.getDependencies().getMaxAdvisories()).isEqualTo(24);
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
                            BeansController.class,
                            BootUiIndexController.class,
                            CacheController.class,
                            ClaudeCodeController.class,
                            ConditionsController.class,
                            ConfigController.class,
                            CopilotController.class,
                            DataController.class,
                            DependenciesController.class,
                            DevToolsController.class,
                            HealthController.class,
                            HttpProbeController.class,
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
                            SecurityController.class,
                            StartupController.class,
                            TracesController.class)
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
    void optionalClasspathPanelsAreRegisteredWhenDependenciesArePresent() {
        runner.withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(CacheController.class)
                        .hasSingleBean(DataController.class)
                        .hasSingleBean(LogTailController.class)
                        .hasSingleBean(ScheduledController.class)
                        .hasSingleBean(SecurityController.class));
    }

    @Test
    void skipsSpringDataPanelWhenSpringDataRepositoryMetadataIsMissing() {
        runner.withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("org.springframework.data.repository.core.support"))
                .run(context -> assertThat(context).doesNotHaveBean(DataController.class));
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
    void skipsSecurityPanelWhenSpringSecurityWebIsMissing() {
        runner.withPropertyValues("bootui.enabled=ON")
                .withClassLoader(new FilteredClassLoader("org.springframework.security.web.FilterChainProxy"))
                .run(context -> assertThat(context).doesNotHaveBean(SecurityController.class));
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
}
