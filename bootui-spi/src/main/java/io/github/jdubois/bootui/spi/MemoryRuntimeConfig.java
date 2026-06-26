package io.github.jdubois.bootui.spi;

/**
 * Live application-runtime answers the Live Memory / JVM Tuning report needs, expressed as
 * framework-neutral semantic questions rather than raw configuration property names.
 *
 * <p>This is the seam behind the memory report: the engine asks <em>whether</em> virtual threads are
 * enabled (which changes per-thread stack sizing) and <em>whether</em> Kubernetes liveness/readiness
 * health probes are exposed (which changes the generated manifest), but <em>how</em> those facts are
 * derived is a per-framework detail. The Spring Boot adapter resolves them from
 * {@code spring.threads.virtual.enabled} and the {@code management.endpoint.health.*} chain; a Quarkus
 * adapter would resolve them from its own equivalents (e.g. SmallRye Health). Both are read <em>live</em>
 * on every report so a runtime override is honored.
 */
public interface MemoryRuntimeConfig {

    /** Whether the application is configured to run on virtual threads. */
    boolean virtualThreadsEnabled();

    /** Whether Kubernetes liveness/readiness health probes are exposed by the application. */
    boolean kubernetesHealthProbesEnabled();

    /**
     * Neutral defaults used when no adapter binding is supplied (e.g. the no-arg engine constructor or
     * unit tests): virtual threads off, health probes assumed on. These match the original Spring
     * behavior for an absent {@code Environment}.
     */
    MemoryRuntimeConfig DEFAULTS = new MemoryRuntimeConfig() {

        @Override
        public boolean virtualThreadsEnabled() {
            return false;
        }

        @Override
        public boolean kubernetesHealthProbesEnabled() {
            return true;
        }
    };
}
