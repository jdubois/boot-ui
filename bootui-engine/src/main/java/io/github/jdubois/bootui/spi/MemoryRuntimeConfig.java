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

    /**
     * The application-wide configuration property that toggles virtual threads, or {@code null} when the
     * platform has no such switch. Spring Boot exposes {@code spring.threads.virtual.enabled}; Quarkus has
     * no application-wide equivalent (virtual threads are opted into per endpoint with
     * {@code @RunOnVirtualThread}), so it returns {@code null}. The JVM Tuning panel uses this to decide
     * whether to render the app-wide virtual-threads advisory at all, and which property name to cite.
     */
    String virtualThreadsProperty();

    /** Whether Kubernetes liveness/readiness health probes are exposed by the application. */
    boolean kubernetesHealthProbesEnabled();

    /**
     * How this application exposes its Kubernetes health-probe endpoints: the startup, liveness and
     * readiness HTTP paths plus the optional environment variable that turns them on. This lets the
     * engine render the Kubernetes manifest without hardcoding any one framework's URLs or switch.
     */
    HealthProbeManifest healthProbeManifest();

    /**
     * Neutral defaults used when no adapter binding is supplied (e.g. the no-arg engine constructor or
     * unit tests): virtual threads off (toggled by the Spring property), health probes assumed on, and the
     * Spring Actuator health-probe manifest. These match the original Spring behavior for an absent
     * {@code Environment}; real adapters always supply their own {@link #healthProbeManifest()}, so this
     * fallback is never used in production.
     */
    MemoryRuntimeConfig DEFAULTS = new MemoryRuntimeConfig() {

        @Override
        public boolean virtualThreadsEnabled() {
            return false;
        }

        @Override
        public String virtualThreadsProperty() {
            return "spring.threads.virtual.enabled";
        }

        @Override
        public boolean kubernetesHealthProbesEnabled() {
            return true;
        }

        @Override
        public HealthProbeManifest healthProbeManifest() {
            return HealthProbeManifest.SPRING_ACTUATOR;
        }
    };
}
