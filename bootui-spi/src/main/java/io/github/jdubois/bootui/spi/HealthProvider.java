package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;

/**
 * Framework-neutral seam behind the Health panel: it returns the host application's aggregated health
 * tree, already mapped onto BootUI's neutral {@link HealthNodeDto}.
 *
 * <p>The Spring Boot adapter implements this over Actuator's {@code HealthEndpoint}; the Quarkus adapter
 * implements it over SmallRye Health. Each adapter owns its framework-specific mapping (so the optional
 * health types are concentrated in the adapter and never linked when the backend is absent); the engine
 * {@code HealthService} owns the framework-neutral concerns — the "only default contributors" structural
 * test and the DISABLED / setup-guidance shaping — so both adapters share them.</p>
 *
 * <p>The platform-specific defaults and guidance copy (the default-contributor names and the setup
 * steps) are supplied separately as a {@code HealthGuidance} record, so the engine can still render the
 * "backend unavailable" guidance when no provider exists (the optional backend type is absent).</p>
 */
public interface HealthProvider {

    /**
     * The aggregated health tree root mapped onto {@link HealthNodeDto} (children nested under
     * {@link HealthNodeDto#components()}), or {@code null} when the health backend is unavailable (for
     * example Actuator present but the health endpoint bean absent). A {@code null} return makes the
     * engine render the DISABLED root with the backend-unavailable guidance.
     */
    HealthNodeDto readRoot();
}
