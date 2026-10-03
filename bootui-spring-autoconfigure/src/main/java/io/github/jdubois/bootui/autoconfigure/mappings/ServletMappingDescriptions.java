package io.github.jdubois.bootui.autoconfigure.mappings;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.MappingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDescription;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDetails;
import org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription;
import org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription.MediaTypeExpressionDescription;

/**
 * Spring MVC's routes, as Actuator describes them under {@code dispatcherServlets}. Loaded by
 * {@link SpringMappingProvider} only when Spring Boot's Web MVC module is present.
 */
final class ServletMappingDescriptions {

    private ServletMappingDescriptions() {}

    static List<MappingDto> flatten(Map<?, ?> dispatchers, BootUiSelfDataFilter selfDataFilter) {
        List<MappingDto> mappings = new ArrayList<>();
        for (Object descriptions : dispatchers.values()) {
            if (!(descriptions instanceof Iterable<?> iterable)) {
                continue;
            }
            for (Object description : iterable) {
                if (description instanceof DispatcherServletMappingDescription dispatcherMapping) {
                    mappings.addAll(toMappings(dispatcherMapping, selfDataFilter));
                }
            }
        }
        return mappings;
    }

    private static List<MappingDto> toMappings(
            DispatcherServletMappingDescription description, BootUiSelfDataFilter selfDataFilter) {
        DispatcherServletMappingDetails details = description.getDetails();
        RequestMappingConditionsDescription conditions = details == null ? null : details.getRequestMappingConditions();
        String predicate = description.getPredicate() == null ? "(any)" : description.getPredicate();
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
