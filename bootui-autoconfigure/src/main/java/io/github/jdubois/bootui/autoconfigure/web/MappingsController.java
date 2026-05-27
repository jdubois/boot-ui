package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.MappingDto;
import io.github.jdubois.bootui.core.BootUiDtos.MappingsReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ApplicationMappingsDescriptor;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint.ContextMappingsDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/mappings")
public class MappingsController {

    private final ObjectProvider<MappingsEndpoint> endpoint;

    private final int maxMappings;

    public MappingsController(ObjectProvider<MappingsEndpoint> endpoint) {
        this(endpoint, new BootUiProperties());
    }

    @Autowired
    public MappingsController(ObjectProvider<MappingsEndpoint> endpoint, BootUiProperties properties) {
        this.endpoint = endpoint;
        this.maxMappings = Math.max(0, properties.getLimits().getMaxMappings());
    }

    @GetMapping
    public MappingsReport mappings() {
        MappingsEndpoint me = endpoint.getIfAvailable();
        if (me == null) {
            return new MappingsReport(0, false, List.of());
        }
        ApplicationMappingsDescriptor descriptor = me.mappings();
        List<MappingDto> mappings = new ArrayList<>();
        if (descriptor != null && descriptor.getContexts() != null) {
            for (Map.Entry<String, ContextMappingsDescriptor> entry : descriptor.getContexts().entrySet()) {
                collectMappings(entry.getValue(), mappings);
            }
        }
        mappings.sort(Comparator.comparing(MappingDto::pattern, Comparator.nullsLast(String::compareTo))
                .thenComparing(MappingDto::method, Comparator.nullsLast(String::compareTo))
                .thenComparing(MappingDto::handler, Comparator.nullsLast(String::compareTo)));
        int total = mappings.size();
        boolean truncated = total > maxMappings;
        if (truncated) {
            mappings = new ArrayList<>(mappings.subList(0, maxMappings));
        }
        return new MappingsReport(total, truncated, mappings);
    }

    private void collectMappings(ContextMappingsDescriptor descriptor, List<MappingDto> mappings) {
        if (descriptor == null || descriptor.getMappings() == null) {
            return;
        }
        collectDispatcherMappings(descriptor.getMappings().get("dispatcherServlets"), mappings);
        collectDispatcherMappings(descriptor.getMappings().get("dispatcherHandlers"), mappings);
    }

    private void collectDispatcherMappings(Object dispatchersObject, List<MappingDto> mappings) {
        if (!(dispatchersObject instanceof Map<?, ?> dispatchers)) {
            return;
        }
        for (Object handlerMappingsObject : dispatchers.values()) {
            if (!(handlerMappingsObject instanceof List<?> handlerMappings)) {
                continue;
            }
            for (Object handlerMapping : handlerMappings) {
                collectHandlerMapping(handlerMapping, mappings);
            }
        }
    }

    private void collectHandlerMapping(Object handlerMappingObject, List<MappingDto> mappings) {
        if (!(handlerMappingObject instanceof Map<?, ?> handlerMapping)) {
            return;
        }
        Map<String, Object> details = asMap(handlerMapping.get("details"));
        Map<String, Object> conditions = asMap(details.get("requestMappingConditions"));
        List<String> patterns = readValues(conditions.get("patterns"));
        if (patterns.isEmpty()) {
            patterns = List.of("(any)");
        }
        List<String> methods = readValues(conditions.get("methods"));
        if (methods.isEmpty()) {
            methods = List.of("ANY");
        }
        String handler = textOrNull(handlerMapping.get("handler"));
        String produces = joinValues(conditions.get("produces"));
        String consumes = joinValues(conditions.get("consumes"));
        for (String method : methods) {
            for (String pattern : patterns) {
                mappings.add(new MappingDto(method, pattern, handler, produces, consumes));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private List<String> readValues(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                String text = textOrNull(item);
                if (text != null) {
                    values.add(text);
                }
            }
            return values;
        }
        String text = textOrNull(value);
        return text == null ? List.of() : List.of(text);
    }

    private String joinValues(Object value) {
        List<String> values = readValues(value);
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private String textOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
