package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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

    @Test
    void nullEnvironmentFallsBackToNeutralDefaults() {
        SpringMemoryRuntimeConfig config = new SpringMemoryRuntimeConfig(null);

        assertThat(config.virtualThreadsEnabled()).isFalse();
        assertThat(config.kubernetesHealthProbesEnabled()).isTrue();
    }
}
