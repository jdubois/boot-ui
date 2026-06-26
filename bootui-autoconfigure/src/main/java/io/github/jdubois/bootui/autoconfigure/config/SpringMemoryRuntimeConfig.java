package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.spi.MemoryRuntimeConfig;
import org.springframework.core.env.Environment;

/**
 * Resolves {@link MemoryRuntimeConfig} answers from the live Spring {@link Environment}.
 *
 * <p>Read live (not snapshotted) so a runtime override of the underlying properties is honored on the
 * next memory report. This is the Spring-specific mapping that previously lived inside the memory
 * report provider before it moved to the framework-neutral engine: {@code spring.threads.virtual.enabled}
 * for virtual threads, and the {@code management.endpoint.health.*} chain for whether Kubernetes
 * liveness/readiness probes are exposed. A {@code null} environment falls back to the same neutral
 * defaults as {@link MemoryRuntimeConfig#DEFAULTS} (virtual threads off, probes on).</p>
 */
public class SpringMemoryRuntimeConfig implements MemoryRuntimeConfig {

    private static final String VIRTUAL_THREADS_PROPERTY = "spring.threads.virtual.enabled";
    private static final String HEALTH_ENDPOINT_ENABLED_PROPERTY = "management.endpoint.health.enabled";
    private static final String HEALTH_PROBES_ENABLED_PROPERTY = "management.endpoint.health.probes.enabled";
    private static final String ENDPOINTS_ENABLED_BY_DEFAULT_PROPERTY = "management.endpoints.enabled-by-default";

    private final Environment environment;

    public SpringMemoryRuntimeConfig(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean virtualThreadsEnabled() {
        if (environment == null || !environment.containsProperty(VIRTUAL_THREADS_PROPERTY)) {
            return false;
        }
        Boolean configured = environment.getProperty(VIRTUAL_THREADS_PROPERTY, Boolean.class);
        return configured != null && configured;
    }

    @Override
    public boolean kubernetesHealthProbesEnabled() {
        if (environment == null) {
            return true;
        }
        boolean endpointsEnabledByDefault =
                environment.getProperty(ENDPOINTS_ENABLED_BY_DEFAULT_PROPERTY, Boolean.class, true);
        boolean healthEndpointEnabled =
                environment.getProperty(HEALTH_ENDPOINT_ENABLED_PROPERTY, Boolean.class, endpointsEnabledByDefault);
        if (!healthEndpointEnabled) {
            return false;
        }
        return environment.getProperty(HEALTH_PROBES_ENABLED_PROPERTY, Boolean.class, true);
    }
}
