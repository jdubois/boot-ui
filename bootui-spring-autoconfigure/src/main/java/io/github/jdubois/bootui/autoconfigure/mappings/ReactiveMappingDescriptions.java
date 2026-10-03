package io.github.jdubois.bootui.autoconfigure.mappings;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.webflux.actuate.web.mappings.DispatcherHandlerMappingDescription;
import org.springframework.boot.webflux.actuate.web.mappings.DispatcherHandlerMappingDetails;
import org.springframework.boot.webflux.actuate.web.mappings.RequestMappingConditionsDescription;
import org.springframework.boot.webflux.actuate.web.mappings.RequestMappingConditionsDescription.MediaTypeExpressionDescription;

/**
 * Spring WebFlux's routes, as Actuator describes them under {@code dispatcherHandlers}: annotated controllers with
 * their request-mapping conditions, and functional routes by their predicate. Loaded by {@link SpringMappingProvider}
 * only when Spring Boot's WebFlux module is present.
 */
final class ReactiveMappingDescriptions {

    /**
     * A functional route's predicate naming one method and one path, such as {@code (GET && /api/items/{id})}, which
     * a {@code RouterFunction} describes itself with; any other predicate is kept whole.
     */
    private static final Pattern METHOD_AND_PATH =
            Pattern.compile("^\\(?\\s*(GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS)\\s*&&\\s*(/[^\\s()&|!]*)\\s*\\)?$");

    private ReactiveMappingDescriptions() {}

    static List<MappingDto> flatten(Map<?, ?> dispatchers, BootUiSelfDataFilter selfDataFilter) {
        List<MappingDto> mappings = new ArrayList<>();
        for (Object descriptions : dispatchers.values()) {
            if (!(descriptions instanceof Iterable<?> iterable)) {
                continue;
            }
            for (Object description : iterable) {
                if (description instanceof DispatcherHandlerMappingDescription handlerMapping) {
                    mappings.addAll(toMappings(handlerMapping, selfDataFilter));
                }
            }
        }
        return mappings;
    }

    private static List<MappingDto> toMappings(
            DispatcherHandlerMappingDescription description, BootUiSelfDataFilter selfDataFilter) {
        DispatcherHandlerMappingDetails details = description.getDetails();
        RequestMappingConditionsDescription conditions = details == null ? null : details.getRequestMappingConditions();
        String predicate = description.getPredicate() == null ? "(any)" : description.getPredicate();
        if (conditions == null && details != null && details.getHandlerFunction() != null) {
            Matcher route = METHOD_AND_PATH.matcher(predicate.trim());
            if (route.matches()) {
                return MappingRows.of(
                        Set.of(route.group(2)),
                        Set.of(route.group(1)),
                        predicate,
                        description.getHandler(),
                        null,
                        null,
                        selfDataFilter);
            }
        }
        return MappingRows.of(
                conditions == null ? null : conditions.getPatterns(),
                conditions == null ? null : conditions.getMethods(),
                predicate,
                description.getHandler(),
                conditions == null ? null : mediaTypes(conditions.getProduces()),
                conditions == null ? null : mediaTypes(conditions.getConsumes()),
                selfDataFilter);
    }

    private static String mediaTypes(List<MediaTypeExpressionDescription> descriptions) {
        if (descriptions == null || descriptions.isEmpty()) {
            return null;
        }
        return MappingRows.mediaTypes(descriptions.stream()
                .map(description -> (description.isNegated() ? "!" : "") + description.getMediaType())
                .toList());
    }
}
