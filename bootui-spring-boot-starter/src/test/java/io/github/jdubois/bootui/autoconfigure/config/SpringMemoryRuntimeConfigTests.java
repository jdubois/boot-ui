package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.context.properties.source.MutuallyExclusiveConfigurationPropertiesException;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link SpringMemoryRuntimeConfig}, the Spring adapter mapping from the live
 * {@code Environment} onto the framework-neutral {@code MemoryRuntimeConfig} questions.
 *
 * <p>These pin the Spring-specific property reading that previously lived inside the memory report
 * provider before it moved to the engine: {@code spring.threads.virtual.enabled} and the
 * {@code management.endpoint.health.*} chain.</p>
 */
class SpringMemoryRuntimeConfigTests {

    @Test
    void virtualThreadsDefaultsToFalseWhenPropertyAbsent() {
        SpringMemoryRuntimeConfig config = new SpringMemoryRuntimeConfig(new MockEnvironment());

        assertThat(config.virtualThreadsEnabled()).isFalse();
    }

    @Test
    void virtualThreadsReflectsProperty() {
        assertThat(new SpringMemoryRuntimeConfig(
                                new MockEnvironment().withProperty("spring.threads.virtual.enabled", "true"))
                        .virtualThreadsEnabled())
                .isTrue();
        assertThat(new SpringMemoryRuntimeConfig(
                                new MockEnvironment().withProperty("spring.threads.virtual.enabled", "false"))
                        .virtualThreadsEnabled())
                .isFalse();
    }

    @Test
    void virtualThreadsPropertyIsTheSpringSwitchEvenWithoutAnEnvironment() {
        assertThat(new SpringMemoryRuntimeConfig(new MockEnvironment()).virtualThreadsProperty())
                .isEqualTo("spring.threads.virtual.enabled");
        assertThat(new SpringMemoryRuntimeConfig(null).virtualThreadsProperty())
                .isEqualTo("spring.threads.virtual.enabled");
    }

    @Test
    void healthProbesDefaultToEnabledWhenNothingConfigured() {
        SpringMemoryRuntimeConfig config = new SpringMemoryRuntimeConfig(new MockEnvironment());

        assertThat(config.kubernetesHealthProbesEnabled()).isTrue();
    }

    @Test
    void healthProbesDisabledWhenProbesPropertyFalse() {
        SpringMemoryRuntimeConfig config = new SpringMemoryRuntimeConfig(
                new MockEnvironment().withProperty("management.endpoint.health.probes.enabled", "false"));

        assertThat(config.kubernetesHealthProbesEnabled()).isFalse();
    }

    @Test
    void healthProbesDisabledWhenHealthEndpointDisabled() {
        SpringMemoryRuntimeConfig config = new SpringMemoryRuntimeConfig(
                new MockEnvironment().withProperty("management.endpoint.health.enabled", "false"));

        assertThat(config.kubernetesHealthProbesEnabled()).isFalse();
    }

    @Test
    void healthProbesDisabledWhenEndpointsDisabledByDefaultAndHealthNotReEnabled() {
        SpringMemoryRuntimeConfig config = new SpringMemoryRuntimeConfig(
                new MockEnvironment().withProperty("management.endpoints.enabled-by-default", "false"));

        assertThat(config.kubernetesHealthProbesEnabled()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("healthProbeSettings")
    void healthCapabilityMatchesNativeMvcAndWebFluxConfiguration(
            boolean healthReadable, boolean probesEnabled, String[] properties) {
        for (boolean reactive : new boolean[] {false, true}) {
            AbstractApplicationContextRunner<?, ?, ?> runner =
                    reactive ? new ReactiveWebApplicationContextRunner() : new WebApplicationContextRunner();
            runner.withInitializer(context ->
                            context.getEnvironment().setConversionService(new ApplicationConversionService()))
                    .withConfiguration(AutoConfigurations.of(
                            EndpointAutoConfiguration.class,
                            WebEndpointAutoConfiguration.class,
                            ApplicationAvailabilityAutoConfiguration.class,
                            HealthContributorRegistryAutoConfiguration.class,
                            HealthEndpointAutoConfiguration.class,
                            AvailabilityProbesAutoConfiguration.class))
                    .withPropertyValues(properties)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(HealthEndpoint.class).isEmpty())
                                .as("native health endpoint absent on reactive=%s", reactive)
                                .isEqualTo(!healthReadable);
                        assertThat(new SpringMemoryRuntimeConfig(context.getEnvironment(), context.getClassLoader())
                                        .kubernetesHealthProbesEnabled())
                                .isEqualTo(healthReadable && probesEnabled);
                        if (healthReadable) {
                            assertThat(context.getBean(HealthEndpointGroups.class)
                                            .getNames())
                                    .containsExactlyInAnyOrderElementsOf(
                                            probesEnabled
                                                    ? java.util.Set.of("liveness", "readiness")
                                                    : java.util.Set.of());
                        }
                    });
        }
    }

    static Stream<Arguments> healthProbeSettings() {
        return Stream.of(
                Arguments.of(true, true, new String[] {}),
                Arguments.of(false, true, new String[] {"management.endpoints.access.default=none"}),
                Arguments.of(false, true, new String[] {"management.endpoints.enabled-by-default=false"}),
                Arguments.of(true, true, new String[] {"management.endpoints.access.default=read-only"}),
                Arguments.of(true, true, new String[] {"management.endpoints.access.default=unrestricted"}),
                Arguments.of(true, true, new String[] {
                    "management.endpoints.access.default=none", "management.endpoint.health.access=read-only"
                }),
                Arguments.of(false, true, new String[] {
                    "management.endpoints.access.default=unrestricted", "management.endpoint.health.access=none"
                }),
                Arguments.of(true, true, new String[] {
                    "management.endpoints.access.default=none", "management.endpoint.health.enabled=true"
                }),
                Arguments.of(true, true, new String[] {
                    "management.endpoints.enabled-by-default=false", "management.endpoint.health.access=read-only"
                }),
                Arguments.of(true, true, new String[] {
                    "management.endpoints.enabled-by-default=false", "management.endpoint.health.enabled=true"
                }),
                Arguments.of(false, true, new String[] {
                    "management.endpoint.health.access=unrestricted", "management.endpoints.access.max-permitted=none"
                }),
                Arguments.of(true, true, new String[] {
                    "management.endpoint.health.access=unrestricted",
                    "management.endpoints.access.max-permitted=read-only"
                }),
                Arguments.of(false, true, new String[] {
                    "management.endpoints.access.default=read-only", "management.endpoint.health.enabled=false"
                }),
                Arguments.of(true, false, new String[] {"management.endpoint.health.probes.enabled=false"}));
    }

    @Test
    void endpointAccessReadsTheLiveEnvironmentRatherThanCachingTheFirstVerdict() {
        MockEnvironment environment = bootEnvironment();
        SpringMemoryRuntimeConfig config = new SpringMemoryRuntimeConfig(environment);

        assertThat(config.kubernetesHealthProbesEnabled()).isTrue();
        environment.withProperty("management.endpoints.access.default", "none");
        assertThat(config.kubernetesHealthProbesEnabled()).isFalse();
        environment.withProperty("management.endpoint.health.enabled", "true");
        assertThat(config.kubernetesHealthProbesEnabled()).isTrue();
        environment.withProperty("management.endpoints.access.max-permitted", "none");
        assertThat(config.kubernetesHealthProbesEnabled()).isFalse();
    }

    @Test
    void invalidOrConflictingAccessIsNotSilentlyReportedAsEnabled() {
        assertThatThrownBy(() -> new SpringMemoryRuntimeConfig(bootEnvironment()
                                .withProperty("management.endpoints.access.default", "none")
                                .withProperty("management.endpoints.enabled-by-default", "true"))
                        .kubernetesHealthProbesEnabled())
                .isInstanceOf(MutuallyExclusiveConfigurationPropertiesException.class);
        assertThatThrownBy(() -> new SpringMemoryRuntimeConfig(bootEnvironment()
                                .withProperty("management.endpoint.health.access", "none")
                                .withProperty("management.endpoint.health.enabled", "true"))
                        .kubernetesHealthProbesEnabled())
                .isInstanceOf(MutuallyExclusiveConfigurationPropertiesException.class);
        assertThatThrownBy(() -> new SpringMemoryRuntimeConfig(
                                bootEnvironment().withProperty("management.endpoint.health.access", "invalid"))
                        .kubernetesHealthProbesEnabled())
                .isInstanceOf(ConversionFailedException.class);
    }

    @Test
    void missingActuatorHealthOrAccessResolverDisablesTheCapabilityWithoutLinkingOptionalTypes() {
        for (String absent : new String[] {
            "org.springframework.boot.health.actuate.endpoint.HealthEndpoint",
            "org.springframework.boot.actuate.autoconfigure.endpoint.PropertiesEndpointAccessResolver"
        }) {
            SpringMemoryRuntimeConfig config =
                    new SpringMemoryRuntimeConfig(new MockEnvironment(), new FilteredClassLoader(absent));

            assertThat(config.kubernetesHealthProbesEnabled()).as(absent).isFalse();
        }
    }

    @Test
    void nullEnvironmentFallsBackToNeutralDefaults() {
        SpringMemoryRuntimeConfig config = new SpringMemoryRuntimeConfig(null);

        assertThat(config.virtualThreadsEnabled()).isFalse();
        assertThat(config.kubernetesHealthProbesEnabled()).isTrue();
    }

    private static MockEnvironment bootEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setConversionService(new ApplicationConversionService());
        return environment;
    }
}
