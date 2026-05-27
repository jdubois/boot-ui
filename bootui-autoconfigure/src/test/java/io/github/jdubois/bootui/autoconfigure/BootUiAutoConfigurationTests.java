package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.autoconfigure.safety.LocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BootUiAutoConfigurationTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class));

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
                .hasSingleBean(ConfigOverrideService.class)
                .hasSingleBean(DevToolsBridge.class)
                .hasSingleBean(DevToolsController.class)
                .hasSingleBean(OverviewController.class)
                .hasSingleBean(DevServicesController.class)
                .hasSingleBean(DependenciesController.class)
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
        runner.withPropertyValues("bootui.enabled=ON")
            .run(context -> {
                BootUiProperties properties = context.getBean(BootUiProperties.class);
                assertThat(properties.getEnabled()).isEqualTo(BootUiProperties.Mode.ON);
                assertThat(properties.getPath()).isEqualTo("/bootui");
                assertThat(properties.getApiPath()).isEqualTo("/bootui/api");
                assertThat(properties.isLocalhostOnly()).isTrue();
                assertThat(properties.isAllowNonLocalhost()).isFalse();
                assertThat(properties.isMaskSecrets()).isTrue();
                assertThat(properties.getExposeValues()).isEqualTo(BootUiProperties.ValueExposure.MASKED);
                assertThat(properties.isShowBanner()).isTrue();
                assertThat(properties.getEnabledProfiles()).containsExactly("dev", "local");
                assertThat(properties.getDisabledProfiles()).containsExactly("prod", "production");
                assertThat(properties.getOverridesFile()).isEqualTo(".bootui/application-bootui.properties");
                assertThat(properties.getEndpointTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.getDevServices().isRestartEnabled()).isFalse();
                assertThat(properties.getDevServices().getLogTailBytes()).isEqualTo(64 * 1024);
                assertThat(properties.getCache().isClearEnabled()).isTrue();
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
                "bootui.overrides-file=/tmp/bootui.properties",
                "bootui.endpoint-timeout=2s",
                "bootui.dev-services.restart-enabled=true",
                "bootui.dev-services.log-tail-bytes=2048",
                "bootui.cache.clear-enabled=false",
                "bootui.dependencies.osv-enabled=false",
                "bootui.dependencies.max-packages=42",
                "bootui.dependencies.max-advisories=24")
            .run(context -> {
                BootUiProperties properties = context.getBean(BootUiProperties.class);
                assertThat(properties.getPath()).isEqualTo("/admin");
                assertThat(properties.getApiPath()).isEqualTo("/admin/api");
                assertThat(properties.isMaskSecrets()).isFalse();
                assertThat(properties.getExposeValues())
                    .isEqualTo(BootUiProperties.ValueExposure.FULL);
                assertThat(properties.getOverridesFile()).isEqualTo("/tmp/bootui.properties");
                assertThat(properties.getEndpointTimeout()).isEqualTo(Duration.ofSeconds(2));
                assertThat(properties.getDevServices().isRestartEnabled()).isTrue();
                assertThat(properties.getDevServices().getLogTailBytes()).isEqualTo(2048);
                assertThat(properties.getCache().isClearEnabled()).isFalse();
                assertThat(properties.getDependencies().isOsvEnabled()).isFalse();
                assertThat(properties.getDependencies().getMaxPackages()).isEqualTo(42);
                assertThat(properties.getDependencies().getMaxAdvisories()).isEqualTo(24);
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
}
