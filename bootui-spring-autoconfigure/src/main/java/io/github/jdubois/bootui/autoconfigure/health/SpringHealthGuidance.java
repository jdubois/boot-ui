package io.github.jdubois.bootui.autoconfigure.health;

import io.github.jdubois.bootui.core.dto.HealthSetupStepDto;
import io.github.jdubois.bootui.engine.health.HealthGuidance;
import java.util.List;
import java.util.Set;

/**
 * Spring Boot's platform-specific Health guidance: the default-contributor names and the setup-step copy
 * that the engine {@link io.github.jdubois.bootui.engine.health.HealthService} renders.
 *
 * <p>This holder references only neutral core DTOs (no Actuator types), so it is safe to load on the
 * always-active path even when the Actuator JAR is absent — that is exactly when the engine needs the
 * {@link HealthGuidance#unavailableReason()} / {@link HealthGuidance#unavailableSetup()} to render the
 * DISABLED root. The copy is moved verbatim from the former {@code HealthController}.</p>
 */
public final class SpringHealthGuidance {

    private static final String ACTUATOR_UNAVAILABLE_REASON = "Spring Boot Actuator health endpoint is not available";

    private static final String DEFAULT_CONTRIBUTORS_REASON =
            "Only Spring Boot default health indicators are available";

    private static final Set<String> DEFAULT_HEALTH_CONTRIBUTORS =
            Set.of("diskSpace", "livenessState", "readinessState", "ping", "ssl");

    private static final List<HealthSetupStepDto> ACTUATOR_SETUP = List.of(
            new HealthSetupStepDto(
                    "Add Spring Boot Actuator",
                    "Use bootui-spring-boot-starter, or add spring-boot-starter-actuator alongside"
                            + " bootui-spring-autoconfigure so Spring creates the HealthEndpoint bean.",
                    List.of("org.springframework.boot:spring-boot-starter-actuator")),
            new HealthSetupStepDto(
                    "Expose health details locally",
                    "BootUI can render contributors and details when the local Actuator health endpoint exposes them.",
                    List.of(
                            "management.endpoints.web.exposure.include=health,info",
                            "management.endpoint.health.show-details=always")),
            new HealthSetupStepDto(
                    "Enable availability probes when you need them",
                    "Keep probes enabled and point your runtime platform at the liveness and readiness health groups.",
                    List.of("management.endpoint.health.probes.enabled=true")),
            new HealthSetupStepDto(
                    "Configure SSL certificate health intentionally",
                    "Configure Spring SSL bundles when you want certificate checks, or disable the SSL indicator when"
                            + " the application has no SSL bundles to validate.",
                    List.of("spring.ssl.bundle.*", "management.health.ssl.enabled=false")));

    private static final List<HealthSetupStepDto> DEFAULT_CONTRIBUTOR_SETUP = List.of(
            new HealthSetupStepDto(
                    "Add application health contributors",
                    "Create a HealthIndicator bean or add starters that provide dependency indicators, such as JDBC,"
                            + " Redis, RabbitMQ, or other services your app depends on.",
                    List.of("class MyHealthIndicator implements HealthIndicator")),
            new HealthSetupStepDto(
                    "Keep availability probes explicit",
                    "Use liveness and readiness for platform probes, but do not treat them as dependency health until"
                            + " application-specific contributors are present.",
                    List.of("management.endpoint.health.probes.enabled=true")),
            new HealthSetupStepDto(
                    "Configure or disable SSL certificate health",
                    "Configure Spring SSL bundles when certificate health matters, or disable the SSL indicator for"
                            + " applications without SSL bundles.",
                    List.of("spring.ssl.bundle.*", "management.health.ssl.enabled=false")));

    /** Spring Boot's Health guidance, shared by the engine service (immutable, thread-safe). */
    public static final HealthGuidance INSTANCE = new HealthGuidance(
            DEFAULT_HEALTH_CONTRIBUTORS,
            ACTUATOR_UNAVAILABLE_REASON,
            ACTUATOR_SETUP,
            DEFAULT_CONTRIBUTORS_REASON,
            DEFAULT_CONTRIBUTOR_SETUP);

    private SpringHealthGuidance() {}
}
