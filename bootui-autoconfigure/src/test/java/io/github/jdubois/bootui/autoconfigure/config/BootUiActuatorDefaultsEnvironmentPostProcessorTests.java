package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
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
        assertThat(env.getProperty(
                        BootUiActuatorDefaultsEnvironmentPostProcessor.TRACING_SAMPLING_PROBABILITY_PROPERTY))
                .isEqualTo(BootUiActuatorDefaultsEnvironmentPostProcessor.TRACING_SAMPLING_PROBABILITY);
        assertThat(env.getPropertySources()
                        .contains(BootUiActuatorDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isTrue();
        assertThat(env.getPropertySources()
                        .contains("defaultProperties"))
                .isFalse();
    }

    @Test
    void keepsUserConfiguredActuatorExposure() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("management.endpoints.web.exposure.include", "health,info")
                .withProperty("management.endpoint.health.show-details", "never")
                .withProperty(
                        BootUiActuatorDefaultsEnvironmentPostProcessor.TRACING_SAMPLING_PROBABILITY_PROPERTY, "0.25");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info");
        assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(env.getProperty(
                        BootUiActuatorDefaultsEnvironmentPostProcessor.TRACING_SAMPLING_PROBABILITY_PROPERTY))
                .isEqualTo("0.25");
    }

    @Test
    void doesNotContributeTracingDefaultsWhenTelemetryIsDisabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("bootui.telemetry.enabled", "false");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo(BootUiActuatorDefaultsEnvironmentPostProcessor.REQUIRED_ENDPOINTS);
        assertThat(env.getProperty(
                        BootUiActuatorDefaultsEnvironmentPostProcessor.TRACING_SAMPLING_PROBABILITY_PROPERTY))
                .isNull();
    }

    @Test
    void doesNotContributeTracingDefaultsWhenTracesPanelIsDisabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("bootui.panels.traces.enabled", "false");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo(BootUiActuatorDefaultsEnvironmentPostProcessor.REQUIRED_ENDPOINTS);
        assertThat(env.getProperty(
                        BootUiActuatorDefaultsEnvironmentPostProcessor.TRACING_SAMPLING_PROBABILITY_PROPERTY))
                .isNull();
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
        assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 10);
    }

    @Test
    void keepsExistingDefaultPropertiesValues() {
        MockEnvironment env = new MockEnvironment().withProperty("bootui.enabled", "ON");
        env.getPropertySources()
                .addLast(new MapPropertySource(
                        "defaultProperties",
                        new LinkedHashMap<>(Map.of(
                                "management.endpoints.web.exposure.include",
                                "health,info,prometheus",
                                "management.endpoint.health.show-details",
                                "never"))));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,prometheus");
        assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
    }

    @Test
    void laterAddLastDefaultsOverrideBootUiDefaults() {
        MockEnvironment env = new MockEnvironment().withProperty("bootui.enabled", "ON");
        env.getPropertySources().addLast(new MapPropertySource(
                "libraryDefaults", Map.of("management.endpoints.web.exposure.include", "*")));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("*");
    }
}
