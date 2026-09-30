package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Where each modelled controller, handler method, exception handler, and throwing endpoint was declared, keyed by
 * the very model instance a rule reports on. Identity keys matter: two handler models can be equal by value (for
 * example overloads with identical facts) while sitting on different lines.
 *
 * <p>Each entry also keeps a scan-local identity of the declaring element, such as a method's full name with its
 * parameter types, so a rule that merges several models into one finding can tell whether they are one element.
 * Two locations can be equal while naming different overloads.</p>
 */
final class RestApiLocations {

    private static final RestApiLocations NONE = new RestApiLocations(Map.of());

    record Element(AdvisorViolationLocationDto location, String identity) {}

    private final Map<Object, Element> elements;

    private RestApiLocations(Map<Object, Element> elements) {
        this.elements = elements;
    }

    static RestApiLocations none() {
        return NONE;
    }

    static RestApiLocations of(IdentityHashMap<Object, Element> elements) {
        return new RestApiLocations(new IdentityHashMap<>(elements));
    }

    /** The location of {@code model}, or {@code null} when it was not built from one located element. */
    AdvisorViolationLocationDto of(Object model) {
        Element element = model == null ? null : elements.get(model);
        return element == null ? null : element.location();
    }

    /** The scan-local identity of the element {@code model} was built from, or {@code null} when unknown. */
    String identity(Object model) {
        Element element = model == null ? null : elements.get(model);
        return element == null ? null : element.identity();
    }
}
