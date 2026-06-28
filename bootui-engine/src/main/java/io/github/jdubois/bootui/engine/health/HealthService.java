package io.github.jdubois.bootui.engine.health;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.core.dto.HealthSetupStepDto;
import io.github.jdubois.bootui.spi.HealthProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Framework-neutral Health panel service.
 *
 * <p>It reads the aggregated health tree from a {@link HealthProvider} (each adapter maps its
 * framework-specific health backend onto the neutral {@link HealthNodeDto} there) and applies the two
 * shared concerns: rendering a DISABLED root with setup guidance when no backend is available, and
 * adding "only default contributors are present" guidance when a healthy tree contains nothing but the
 * framework's built-in contributors. The platform-specific defaults and copy come from
 * {@link HealthGuidance}.</p>
 *
 * <p>The provider is nullable so the engine still serves the DISABLED root (with the unavailable
 * guidance) when the optional backend type is absent and no provider bean exists.</p>
 */
public final class HealthService {

    private final HealthProvider provider;
    private final HealthGuidance guidance;

    public HealthService(HealthProvider provider, HealthGuidance guidance) {
        this.provider = provider;
        this.guidance = guidance;
    }

    public HealthNodeDto health() {
        HealthNodeDto root = provider == null ? null : provider.readRoot();
        if (root == null) {
            return disabledRoot(guidance.unavailableReason(), guidance.unavailableSetup());
        }
        if (hasOnlyDefaultContributors(root)) {
            return withGuidance(root, guidance.defaultContributorReason(), guidance.defaultContributorSetup());
        }
        return root;
    }

    private HealthNodeDto disabledRoot(String reason, List<HealthSetupStepDto> setup) {
        return new HealthNodeDto("application", "DISABLED", null, List.of(), false, reason, null, setup);
    }

    private HealthNodeDto withGuidance(HealthNodeDto root, String reason, List<HealthSetupStepDto> setup) {
        return new HealthNodeDto(
                root.name(), root.status(), root.details(), root.components(), true, null, reason, setup);
    }

    private boolean hasOnlyDefaultContributors(HealthNodeDto root) {
        List<HealthNodeDto> components = new ArrayList<>();
        collectComponents(root.components(), components);
        Set<String> componentNames =
                components.stream().map(HealthNodeDto::name).collect(Collectors.toCollection(LinkedHashSet::new));
        return isHealthyDefaultStatus(root)
                && !components.isEmpty()
                && guidance.defaultContributorNames().containsAll(componentNames)
                && components.stream().allMatch(this::isHealthyDefaultStatus);
    }

    private void collectComponents(List<HealthNodeDto> nodes, List<HealthNodeDto> components) {
        for (HealthNodeDto node : nodes) {
            components.add(node);
            collectComponents(node.components(), components);
        }
    }

    private boolean isHealthyDefaultStatus(HealthNodeDto node) {
        return "UP".equals(node.status()) || "UNKNOWN".equals(node.status());
    }
}
