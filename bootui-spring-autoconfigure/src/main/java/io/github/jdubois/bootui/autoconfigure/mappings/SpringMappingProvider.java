package io.github.jdubois.bootui.autoconfigure.mappings;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ContextMappingsDescriptor;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDescription;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDetails;
import org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription;
import org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription.MediaTypeExpressionDescription;

/**
 * Spring Boot {@link MappingProvider} backed by Actuator's {@link MappingsEndpoint}.
 *
 * <p>This class is the single touch-point for the Actuator mappings descriptor types, and it is only
 * instantiated inside the {@code @ConditionalOnClass} nested configuration in
 * {@code BootUiEngineConfiguration}, so {@link MappingsEndpoint} and the descriptor types are never
 * linked in an Actuator-absent application. The endpoint is resolved <em>live</em> through a supplier
 * because the endpoint bean may be absent (Actuator present but the mappings endpoint not exposed), in
 * which case {@link #available()} reports {@code false} and the engine serves an empty report.</p>
 *
 * <p>The flattening and BootUI self-data filtering live here (not in the engine) on purpose: the raw
 * {@link DispatcherServletMappingDescription#getPredicate() predicate} string that
 * {@link BootUiSelfDataFilter#isBootUiMapping} inspects is lost once a row is flattened to a
 * {@link MappingDto} (the DTO has no predicate field). Filtering while the predicate string is still
 * available is byte-identical to the original controller; the engine {@code MappingsService} then only
 * sorts, queries and pages.</p>
 */
public final class SpringMappingProvider implements MappingProvider {

    private final Supplier<MappingsEndpoint> endpoint;

    private final BootUiSelfDataFilter selfDataFilter;

    public SpringMappingProvider(Supplier<MappingsEndpoint> endpoint, BootUiSelfDataFilter selfDataFilter) {
        this.endpoint = endpoint;
        this.selfDataFilter = selfDataFilter;
    }

    @Override
    public boolean available() {
        return endpoint.get() != null;
    }

    @Override
    public List<MappingDto> mappings() {
        MappingsEndpoint me = endpoint.get();
        if (me == null) {
            return List.of();
        }
        return flatten(me.mappings());
    }

    private List<MappingDto> flatten(ApplicationMappingsDescriptor descriptor) {
        List<MappingDto> mappings = new ArrayList<>();
        for (ContextMappingsDescriptor context : descriptor.getContexts().values()) {
            Object dispatcherServlets = context.getMappings().get("dispatcherServlets");
            if (!(dispatcherServlets instanceof Map<?, ?> dispatchers)) {
                continue;
            }
            for (Object descriptions : dispatchers.values()) {
                if (!(descriptions instanceof Iterable<?> iterable)) {
                    continue;
                }
                for (Object description : iterable) {
                    if (description instanceof DispatcherServletMappingDescription dispatcherMapping) {
                        mappings.addAll(toMappings(dispatcherMapping));
                    }
                }
            }
        }
        return mappings;
    }

    private List<MappingDto> toMappings(DispatcherServletMappingDescription description) {
        DispatcherServletMappingDetails details = description.getDetails();
        RequestMappingConditionsDescription conditions = details == null ? null : details.getRequestMappingConditions();
        Set<String> patterns = conditions == null ? Set.of() : conditions.getPatterns();
        Set<?> methods = conditions == null ? Set.of() : conditions.getMethods();
        List<String> safePatterns =
                patterns == null || patterns.isEmpty() ? List.of(predicate(description)) : new ArrayList<>(patterns);
        List<String> safeMethods = methods == null || methods.isEmpty()
                ? List.of("ANY")
                : methods.stream().map(Object::toString).sorted().toList();
        String produces = conditions == null ? null : mediaTypes(conditions.getProduces());
        String consumes = conditions == null ? null : mediaTypes(conditions.getConsumes());
        String predicate = predicate(description);
        String handler = description.getHandler();
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

    private String mediaTypes(List<MediaTypeExpressionDescription> descriptions) {
        if (descriptions == null || descriptions.isEmpty()) {
            return null;
        }
        return descriptions.stream()
                .map(description -> (description.isNegated() ? "!" : "") + description.getMediaType())
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
    }

    private String predicate(DispatcherServletMappingDescription description) {
        return description.getPredicate() == null ? "(any)" : description.getPredicate();
    }
}
