package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.MappingDto;
import io.github.jdubois.bootui.core.BootUiDtos.MappingsReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ContextMappingsDescriptor;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDescription;
import org.springframework.boot.webmvc.actuate.web.mappings.DispatcherServletMappingDetails;
import org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription;
import org.springframework.boot.webmvc.actuate.web.mappings.RequestMappingConditionsDescription.MediaTypeExpressionDescription;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/mappings")
public class MappingsController {

    private final ObjectProvider<MappingsEndpoint> endpoint;

    public MappingsController(ObjectProvider<MappingsEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping
    public ResponseEntity<ApplicationMappingsDescriptor> mappings() {
        MappingsEndpoint me = endpoint.getIfAvailable();
        if (me == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(me.mappings());
    }

    @GetMapping("/flat")
    public MappingsReport flatMappings(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        MappingsEndpoint me = endpoint.getIfAvailable();
        if (me == null) {
            return new MappingsReport(0, List.of());
        }
        List<MappingDto> mappings = flatten(me.mappings());
        Comparator<String> nullSafeString = Comparator.nullsLast(String::compareTo);
        mappings.sort(Comparator.comparing(MappingDto::pattern, nullSafeString)
                .thenComparing(MappingDto::method, nullSafeString)
                .thenComparing(MappingDto::handler, nullSafeString));
        String normalizedQuery = PagedList.normalize(query);
        PagedList.Result<MappingDto> page =
                PagedList.from(mappings, mapping -> matchesQuery(mapping, normalizedQuery), offset, limit);
        return new MappingsReport(mappings.size(), page.items(), page.page());
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
        List<MappingDto> mappings = new ArrayList<>();
        for (String pattern : safePatterns) {
            for (String method : safeMethods) {
                mappings.add(new MappingDto(method, pattern, description.getHandler(), produces, consumes));
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

    private boolean matchesQuery(MappingDto mapping, String query) {
        return PagedList.contains(mapping.method(), query)
                || PagedList.contains(mapping.pattern(), query)
                || PagedList.contains(mapping.handler(), query)
                || PagedList.contains(mapping.produces(), query)
                || PagedList.contains(mapping.consumes(), query);
    }
}
