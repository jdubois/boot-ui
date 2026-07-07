package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
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
        assertThat(env.getProperty("logging.level.io.opentelemetry"))
                .isEqualTo(BootUiActuatorDefaultsEnvironmentPostProcessor.TRACING_LOG_LEVEL);
        assertThat(env.getProperty("logging.level.io.micrometer.tracing"))
                .isEqualTo(BootUiActuatorDefaultsEnvironmentPostProcessor.TRACING_LOG_LEVEL);
        assertThat(env.getPropertySources().contains(DefaultPropertiesPropertySource.NAME))
                .isTrue();
    }

    @Test
    void keepsHostConfiguredTracingLogLevels() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("logging.level.io.opentelemetry", "DEBUG");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("logging.level.io.opentelemetry")).isEqualTo("DEBUG");
        // The other tracing logger the host did not set still gets BootUI's quiet default.
        assertThat(env.getProperty("logging.level.io.micrometer.tracing"))
                .isEqualTo(BootUiActuatorDefaultsEnvironmentPostProcessor.TRACING_LOG_LEVEL);
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
        assertThat(env.getProperty("logging.level.io.opentelemetry")).isNull();
        assertThat(env.getProperty("logging.level.io.micrometer.tracing")).isNull();
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
        assertThat(env.getProperty("logging.level.io.opentelemetry")).isNull();
        assertThat(env.getProperty("logging.level.io.micrometer.tracing")).isNull();
    }

    @Test
    void doesNotContributeDefaultsWhenBootUiIsDisabled() {
        MockEnvironment env = new MockEnvironment();

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getPropertySources().contains(DefaultPropertiesPropertySource.NAME))
                .isFalse();
        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isNull();
    }

    @Test
    void runsAsLowestPrecedenceLibraryDefault() {
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
        env.getPropertySources()
                .addLast(new MapPropertySource(
                        "libraryDefaults", Map.of("management.endpoints.web.exposure.include", "*")));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("*");
    }

    @Test
    void losesToHostDefaultPropertiesAfterSpringBootPinsDefaultsToEnd() {
        MockEnvironment env = new MockEnvironment().withProperty("bootui.enabled", "ON");
        DefaultPropertiesPropertySource.addOrMerge(
                Map.of("management.endpoints.web.exposure.include", "*"), env.getPropertySources());

        processor.postProcessEnvironment(env, new SpringApplication());
        // Spring Boot pins defaultProperties to the bottom after every EnvironmentPostProcessor runs.
        DefaultPropertiesPropertySource.moveToEnd(env);

        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("*");
    }

    @Test
    void losesToLaterPostProcessorDefaultsAfterSpringBootPinsDefaultsToEnd() {
        MockEnvironment env = new MockEnvironment().withProperty("bootui.enabled", "ON");

        processor.postProcessEnvironment(env, new SpringApplication());
        // A library EnvironmentPostProcessor that runs after BootUI contributes its defaults via addLast.
        env.getPropertySources()
                .addLast(new MapPropertySource(
                        "libraryDefaults", Map.of("management.endpoints.web.exposure.include", "*")));
        DefaultPropertiesPropertySource.moveToEnd(env);

        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("*");
    }

    @Test
    void contributesReactorContextPropagationForReactiveAppsWithOpenTelemetry() {
        MockEnvironment env = new MockEnvironment().withProperty("bootui.enabled", "ON");

        processor.postProcessEnvironment(env, applicationWithWebType(WebApplicationType.REACTIVE));

        assertThat(env.getProperty(BootUiActuatorDefaultsEnvironmentPostProcessor.REACTOR_CONTEXT_PROPAGATION_PROPERTY))
                .isEqualTo(BootUiActuatorDefaultsEnvironmentPostProcessor.REACTOR_CONTEXT_PROPAGATION_VALUE);
    }

    @Test
    void doesNotContributeReactorContextPropagationForServletApps() {
        MockEnvironment env = new MockEnvironment().withProperty("bootui.enabled", "ON");

        processor.postProcessEnvironment(env, applicationWithWebType(WebApplicationType.SERVLET));

        assertThat(env.getProperty(BootUiActuatorDefaultsEnvironmentPostProcessor.REACTOR_CONTEXT_PROPAGATION_PROPERTY))
                .isNull();
    }

    @Test
    void keepsHostConfiguredReactorContextPropagation() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty(
                        BootUiActuatorDefaultsEnvironmentPostProcessor.REACTOR_CONTEXT_PROPAGATION_PROPERTY, "limited");

        processor.postProcessEnvironment(env, applicationWithWebType(WebApplicationType.REACTIVE));

        assertThat(env.getProperty(BootUiActuatorDefaultsEnvironmentPostProcessor.REACTOR_CONTEXT_PROPAGATION_PROPERTY))
                .isEqualTo("limited");
    }

    @Test
    void isBootUiActuatorDefaultRecognizesReactorContextPropagation() {
        assertThat(BootUiActuatorDefaultsEnvironmentPostProcessor.isBootUiActuatorDefault(
                        BootUiActuatorDefaultsEnvironmentPostProcessor.REACTOR_CONTEXT_PROPAGATION_PROPERTY, "auto"))
                .isTrue();
        assertThat(BootUiActuatorDefaultsEnvironmentPostProcessor.isBootUiActuatorDefault(
                        BootUiActuatorDefaultsEnvironmentPostProcessor.REACTOR_CONTEXT_PROPAGATION_PROPERTY, "limited"))
                .isFalse();
    }

    private static SpringApplication applicationWithWebType(WebApplicationType type) {
        SpringApplication application = new SpringApplication();
        application.setWebApplicationType(type);
        return application;
    }
}
