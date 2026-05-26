package io.github.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.bootui.autoconfigure.safety.LocalhostOnlyFilter;
import io.github.bootui.autoconfigure.web.DevToolsBridge;
import io.github.bootui.autoconfigure.web.DevToolsController;
import io.github.bootui.autoconfigure.web.OverviewController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

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
    void bindsCustomProperties() {
        runner.withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.path=/admin",
                        "bootui.api-path=/admin/api",
                        "bootui.mask-secrets=false",
                        "bootui.expose-values=FULL")
                .run(context -> {
                    BootUiProperties properties = context.getBean(BootUiProperties.class);
                    assertThat(properties.getPath()).isEqualTo("/admin");
                    assertThat(properties.getApiPath()).isEqualTo("/admin/api");
                    assertThat(properties.isMaskSecrets()).isFalse();
                    assertThat(properties.getExposeValues())
                            .isEqualTo(BootUiProperties.ValueExposure.FULL);
                });
    }
}
