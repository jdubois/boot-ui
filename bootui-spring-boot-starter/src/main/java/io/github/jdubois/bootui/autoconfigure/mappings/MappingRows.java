package io.github.jdubois.bootui.autoconfigure.mappings;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** One described route as Mappings rows, a row per pattern and method, shared by Spring MVC and WebFlux. */
final class MappingRows {

    private MappingRows() {}

    /**
     * The rows of one route: one per pattern and method, its predicate standing for its pattern when it has none, and
     * {@code ANY} for its method when it names none. BootUI's own routes are left out by their raw predicate.
     */
    static List<MappingDto> of(
            Collection<String> patterns,
            Collection<?> methods,
            String predicate,
            String handler,
            String produces,
            String consumes,
            BootUiSelfDataFilter selfDataFilter) {
        List<String> safePatterns =
                patterns == null || patterns.isEmpty() ? List.of(predicate) : new ArrayList<>(patterns);
        List<String> safeMethods = methods == null || methods.isEmpty()
                ? List.of("ANY")
                : methods.stream().map(Object::toString).sorted().toList();
        List<MappingDto> mappings = new ArrayList<>();
        for (String pattern : safePatterns) {
            for (String method : safeMethods) {
                if (!selfDataFilter.shouldIncludeMapping(List.of(pattern), predicate, handler)) {
                    continue;
                }
                mappings.add(new MappingDto(method, pattern, handler, produces, consumes));
            }
        }
        return mappings;
    }

    /** Media type expressions, already rendered with {@code !} when negated, sorted and joined. */
    static String mediaTypes(List<String> expressions) {
        return expressions.stream()
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
    }
}
