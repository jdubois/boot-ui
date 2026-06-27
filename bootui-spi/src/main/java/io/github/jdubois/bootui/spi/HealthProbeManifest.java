package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral description of how an application exposes its Kubernetes health-probe HTTP
 * endpoints. The engine uses this to render the Live Memory / JVM Tuning Kubernetes manifest without
 * hardcoding any one framework's URLs, enabling switch, or advisory copy.
 *
 * <p>{@code enablingEnvVar} is nullable: Spring Boot turns its Actuator Kubernetes probe groups on with
 * an environment variable ({@code MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED}), whereas Quarkus/SmallRye
 * Health has no such switch (you add or remove the extension). When it is {@code null} the generated
 * manifest omits the enabling {@code env} entry entirely.
 *
 * <p>{@code probesOmittedWarning} is the advisory note appended to the recommendation when the probe
 * stanzas are left out, so the wording stays accurate per framework rather than naming Spring Actuator
 * unconditionally.
 */
public record HealthProbeManifest(
        String startupPath,
        String livenessPath,
        String readinessPath,
        String enablingEnvVar,
        String probesOmittedWarning) {

    /**
     * Spring Boot Actuator Kubernetes health groups. The startup probe reuses the liveness group, which
     * matches the manifest BootUI generated before the engine was made framework-neutral.
     */
    public static final HealthProbeManifest SPRING_ACTUATOR = new HealthProbeManifest(
            "/actuator/health/liveness",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED",
            "Spring Boot Actuator probes are omitted from the snippet; enabling them is recommended so Kubernetes can restart or drain unhealthy pods.");

    /**
     * Quarkus SmallRye Health endpoints. SmallRye serves a dedicated startup endpoint
     * ({@code /q/health/started}) distinct from liveness, and has no enabling environment variable.
     */
    public static final HealthProbeManifest QUARKUS_SMALLRYE = new HealthProbeManifest(
            "/q/health/started",
            "/q/health/live",
            "/q/health/ready",
            null,
            "Kubernetes liveness/readiness health probes are omitted from the snippet; enabling them is recommended so Kubernetes can restart or drain unhealthy pods.");
}
