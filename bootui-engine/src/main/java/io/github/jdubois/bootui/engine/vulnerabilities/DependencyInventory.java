package io.github.jdubois.bootui.engine.vulnerabilities;

import io.github.jdubois.bootui.core.dto.DependencyCoverageDto;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import java.util.List;

/**
 * A dependency provider's resolved inventory together with how much of the running application's real JAR
 * set that inventory accounts for.
 *
 * <p>Kept separate from the bare {@link DependencyProvider#dependencies()} list so a provider that
 * <em>can</em> enumerate the application's archives (the Spring classpath catalogue) reports its blind spot,
 * while one whose inventory is authoritative by construction (the Quarkus build-time application model)
 * simply reports complete coverage.</p>
 */
public record DependencyInventory(List<DependencyDto> dependencies, DependencyCoverageDto coverage) {

    public DependencyInventory {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        coverage = coverage == null ? DependencyCoverageDto.unavailable() : coverage;
    }

    /** An inventory that is complete by construction: every dependency is known, nothing was probed. */
    public static DependencyInventory complete(List<DependencyDto> dependencies) {
        List<DependencyDto> resolved = dependencies == null ? List.of() : dependencies;
        return new DependencyInventory(resolved, DependencyCoverageDto.complete(resolved.size()));
    }
}
