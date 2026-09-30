package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Where each modelled controller, handler method, exception handler, and throwing endpoint was declared, keyed by
 * the very model instance a rule reports on. Identity keys matter: two handler models can be equal by value (for
 * example overloads with identical facts) while sitting on different lines.
 */
final class RestApiLocations {

    private static final RestApiLocations NONE = new RestApiLocations(Map.of());

    private final Map<Object, AdvisorViolationLocationDto> locations;

    private RestApiLocations(Map<Object, AdvisorViolationLocationDto> locations) {
        this.locations = locations;
    }

    static RestApiLocations none() {
        return NONE;
    }

    static RestApiLocations of(IdentityHashMap<Object, AdvisorViolationLocationDto> locations) {
        return new RestApiLocations(new IdentityHashMap<>(locations));
    }

    /** The location of {@code model}, or {@code null} when it was not built from one located element. */
    AdvisorViolationLocationDto of(Object model) {
        return model == null ? null : locations.get(model);
    }
}
