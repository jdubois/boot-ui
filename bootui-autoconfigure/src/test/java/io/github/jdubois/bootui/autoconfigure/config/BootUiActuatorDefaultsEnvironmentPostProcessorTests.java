package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class BootUiActuatorDefaultsEnvironmentPostProcessorTests {

    private final BootUiActuatorDefaultsEnvironmentPostProcessor processor =
            new BootUiActuatorDefaultsEnvironmentPostProcessor();

    @Test
    void contributesActuatorDefaultsWhenBootUiIsEnabled() {
        MockEnvironment env = new MockEnvironment().withProperty("bootui.enabled", "ON");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo(BootUiActuatorDefaultsEnvironmentPostProcessor.REQUIRED_ENDPOINTS);
        assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("always");
    }

    @Test
    void keepsUserConfiguredActuatorExposure() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("management.endpoints.web.exposure.include", "health,info")
                .withProperty("management.endpoint.health.show-details", "never");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info");
        assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
    }

    @Test
    void doesNotContributeDefaultsWhenBootUiIsDisabled() {
        MockEnvironment env = new MockEnvironment();

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getPropertySources()
                        .contains(BootUiActuatorDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
    }

    @Test
    void orderIsBetweenOverridesAndStartupProcessors() {
        assertThat(processor.getOrder())
                .isGreaterThan(BootUiOverridesEnvironmentPostProcessor.ORDER)
                .isLessThan(BootUiStartupEnvironmentPostProcessor.ORDER);
    }
}
