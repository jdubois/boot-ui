package io.github.jdubois.bootui.engine.health;

import io.github.jdubois.bootui.core.dto.HealthSetupStepDto;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Platform-specific defaults and setup-guidance copy for the Health panel, supplied by each adapter and
 * consumed by {@link HealthService}.
 *
 * <p>This is deliberately kept separate from {@link io.github.jdubois.bootui.spi.HealthProvider}: when the
 * optional health backend is absent there is no provider, yet the engine must still render the
 * "backend unavailable" guidance. Because this record carries only neutral data (a set of names and
 * immutable {@link HealthSetupStepDto} records) it can be constructed unconditionally by the adapter.</p>
 *
 * @param defaultContributorNames the names of the framework's built-in/default health contributors; when
 *     a healthy tree contains <em>only</em> these the engine adds the default-contributor guidance
 * @param unavailableReason human-readable reason rendered on the DISABLED root when no backend is present
 * @param unavailableSetup setup steps guiding the operator to enable the health backend
 * @param defaultContributorReason reason rendered when only default contributors are present
 * @param defaultContributorSetup setup steps guiding the operator to add application health contributors
 */
public record HealthGuidance(
        Set<String> defaultContributorNames,
        String unavailableReason,
        List<HealthSetupStepDto> unavailableSetup,
        String defaultContributorReason,
        List<HealthSetupStepDto> defaultContributorSetup) {

    public HealthGuidance {
        defaultContributorNames =
                defaultContributorNames == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(defaultContributorNames));
        unavailableSetup = unavailableSetup == null ? List.of() : List.copyOf(unavailableSetup);
        defaultContributorSetup = defaultContributorSetup == null ? List.of() : List.copyOf(defaultContributorSetup);
    }
}
