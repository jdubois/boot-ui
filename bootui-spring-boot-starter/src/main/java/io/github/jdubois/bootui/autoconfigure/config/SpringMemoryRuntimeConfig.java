package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.spi.HealthProbeManifest;
import io.github.jdubois.bootui.spi.MemoryRuntimeConfig;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Resolves {@link MemoryRuntimeConfig} answers from the live Spring {@link Environment}.
 *
 * <p>Read live (not snapshotted) so a runtime override of the underlying properties is honored on the
 * next memory report. This is the Spring-specific mapping that previously lived inside the memory
 * report provider before it moved to the framework-neutral engine: {@code spring.threads.virtual.enabled}
 * for virtual threads, and Spring Boot's endpoint access resolver plus
 * {@code management.endpoint.health.probes.enabled} for configured Kubernetes probe capability.
 * This does not verify deployment dependencies, web exposure, or routing. A {@code null} environment
 * falls back to the same neutral defaults as {@link MemoryRuntimeConfig#DEFAULTS} (virtual threads off,
 * probes on).</p>
 */
public class SpringMemoryRuntimeConfig implements MemoryRuntimeConfig {

    private static final String VIRTUAL_THREADS_PROPERTY = "spring.threads.virtual.enabled";
    private static final String HEALTH_PROBES_ENABLED_PROPERTY = "management.endpoint.health.probes.enabled";

    private final Environment environment;
    private final ClassLoader classLoader;

    public SpringMemoryRuntimeConfig(Environment environment) {
        this(environment, SpringMemoryRuntimeConfig.class.getClassLoader());
    }

    public SpringMemoryRuntimeConfig(Environment environment, ClassLoader classLoader) {
        this.environment = environment;
        this.classLoader = classLoader;
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
    public String virtualThreadsProperty() {
        // Returned unconditionally (even for a null Environment): this is the name of the Spring switch,
        // not its live value, so the JVM Tuning advisory still renders on Spring regardless of binding.
        return VIRTUAL_THREADS_PROPERTY;
    }

    @Override
    public boolean kubernetesHealthProbesEnabled() {
        if (environment == null) {
            return true;
        }
        if (!ClassUtils.isPresent("org.springframework.boot.health.actuate.endpoint.HealthEndpoint", classLoader)
                || !ClassUtils.isPresent(
                        "org.springframework.boot.actuate.autoconfigure.endpoint.PropertiesEndpointAccessResolver",
                        classLoader)) {
            return false;
        }
        return HealthEndpointAccess.readable(environment)
                && environment.getProperty(HEALTH_PROBES_ENABLED_PROPERTY, Boolean.class, true);
    }

    @Override
    public HealthProbeManifest healthProbeManifest() {
        return HealthProbeManifest.SPRING_ACTUATOR;
    }

    private static final class HealthEndpointAccess {
        private static boolean readable(Environment environment) {
            var resolver = new org.springframework.boot.actuate.autoconfigure.endpoint.PropertiesEndpointAccessResolver(
                    environment);
            return resolver.accessFor(
                            org.springframework.boot.actuate.endpoint.EndpointId.of("health"),
                            org.springframework.boot.actuate.endpoint.Access.UNRESTRICTED)
                    != org.springframework.boot.actuate.endpoint.Access.NONE;
        }
    }
}
