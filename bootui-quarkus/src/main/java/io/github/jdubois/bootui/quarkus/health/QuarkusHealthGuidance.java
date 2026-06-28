package io.github.jdubois.bootui.quarkus.health;

import io.github.jdubois.bootui.core.dto.HealthSetupStepDto;
import io.github.jdubois.bootui.engine.health.HealthGuidance;
import java.util.List;
import java.util.Set;

/**
 * Quarkus' platform-specific Health guidance: the default-contributor names and the setup-step copy that the
 * engine {@link io.github.jdubois.bootui.engine.health.HealthService} renders. The Quarkus analogue of the
 * Spring adapter's {@code SpringHealthGuidance}.
 *
 * <p>This holder references only neutral core DTOs (no SmallRye types), so it is safe to construct on the
 * always-active path even when {@code quarkus-smallrye-health} is absent — which is exactly when the engine
 * needs {@link HealthGuidance#unavailableReason()} / {@link HealthGuidance#unavailableSetup()} to render the
 * DISABLED root with setup guidance.</p>
 *
 * <p>Unlike Spring Boot (whose Actuator ships fixed default indicators such as {@code diskSpace}, {@code ping}
 * and the availability-probe groups), SmallRye Health has <em>no</em> framework-default contributors: every
 * check is application-authored. The default-contributor set is therefore deliberately <em>empty</em>, which
 * makes the engine's "only default contributors are present" nudge unreachable on Quarkus (an empty default set
 * can never contain a non-empty set of reported checks) — so {@link #defaultContributorReason} and
 * {@link #defaultContributorSetup} are intentionally left empty/null.</p>
 */
public final class QuarkusHealthGuidance {

    private static final String SMALLRYE_UNAVAILABLE_REASON = "Quarkus SmallRye Health is not available";

    private static final List<HealthSetupStepDto> SMALLRYE_SETUP = List.of(
            new HealthSetupStepDto(
                    "Add Quarkus SmallRye Health",
                    "Add the quarkus-smallrye-health extension so Quarkus aggregates MicroProfile Health checks"
                            + " and BootUI can read the report in-process.",
                    List.of("io.quarkus:quarkus-smallrye-health")),
            new HealthSetupStepDto(
                    "Add application health checks",
                    "Implement MicroProfile @Liveness / @Readiness / @Startup HealthCheck beans, or add extensions"
                            + " that contribute checks (such as the Agroal datasource health check), so the panel"
                            + " reflects real dependency health.",
                    List.of("class MyHealthCheck implements org.eclipse.microprofile.health.HealthCheck")),
            new HealthSetupStepDto(
                    "Inspect the health endpoints when you need them",
                    "SmallRye Health serves /q/health, /q/health/live, /q/health/ready and /q/health/started; BootUI"
                            + " reads the same aggregated report in-process without an HTTP round trip.",
                    List.of("quarkus.smallrye-health.root-path=/q/health")));

    /** Quarkus' Health guidance, shared by the engine service (immutable, thread-safe). */
    public static final HealthGuidance INSTANCE =
            new HealthGuidance(Set.of(), SMALLRYE_UNAVAILABLE_REASON, SMALLRYE_SETUP, null, List.of());

    private QuarkusHealthGuidance() {}
}
