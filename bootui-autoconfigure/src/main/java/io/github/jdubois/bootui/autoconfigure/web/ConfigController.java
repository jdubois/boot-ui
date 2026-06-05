package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.BootUiOverridesPropertySource;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.ConfigOverrideRequest;
import io.github.jdubois.bootui.core.dto.ConfigOverrideResult;
import io.github.jdubois.bootui.core.dto.ConfigPropertyDto;
import io.github.jdubois.bootui.core.dto.ConfigPropertySuggestionDto;
import io.github.jdubois.bootui.core.dto.ConfigReport;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bootui/api/config")
public class ConfigController {

    private final ConfigurableEnvironment environment;

    private final ConfigOverrideService overrideService;

    private final BootUiExposure exposure;

    private final ConfigMetadataCatalog metadataCatalog;

    private final SecretMasker masker = new SecretMasker();

    @Autowired
    public ConfigController(
            ConfigurableEnvironment environment, ConfigOverrideService overrideService, BootUiProperties properties) {
        this(
                environment,
                overrideService,
                new BootUiExposure(environment, properties),
                new ConfigMetadataCatalog(ConfigController.class.getClassLoader()));
    }

    ConfigController(
            ConfigurableEnvironment environment,
            ConfigOverrideService overrideService,
            BootUiProperties properties,
            ConfigMetadataCatalog metadataCatalog) {
        this(environment, overrideService, new BootUiExposure(environment, properties), metadataCatalog);
    }

    ConfigController(
            ConfigurableEnvironment environment,
            ConfigOverrideService overrideService,
            BootUiExposure exposure,
            ConfigMetadataCatalog metadataCatalog) {
        this.environment = environment;
        this.overrideService = overrideService;
        this.exposure = exposure;
        this.metadataCatalog = metadataCatalog;
    }

    @GetMapping
    public ConfigReport list(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "source", required = false) String sourceFilter,
            @RequestParam(name = "overridesOnly", required = false, defaultValue = "false") boolean overridesOnly,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        List<String> sources = new ArrayList<>();
        Map<String, ConfigPropertyDto> merged = new LinkedHashMap<>();

        for (PropertySource<?> source : environment.getPropertySources()) {
            sources.add(source.getName());
            if (!(source instanceof EnumerablePropertySource<?> eps)) {
                continue;
            }
            for (String name : eps.getPropertyNames()) {
                if (merged.containsKey(name)) {
                    continue;
                }
                Object value = eps.getProperty(name);
                merged.put(name, toDto(name, value, source.getName()));
            }
        }

        List<ConfigPropertyDto> sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.comparing(ConfigPropertyDto::name));
        String normalizedQuery = PagedList.normalize(query);
        String normalizedSource = sourceFilter == null ? "" : sourceFilter.trim();
        PagedList.Result<ConfigPropertyDto> page = PagedList.from(
                sorted,
                property -> matchesSource(property, normalizedSource)
                        && matchesOverrideFilter(property, overridesOnly)
                        && matchesQuery(property, normalizedQuery),
                offset,
                limit);
        return new ConfigReport(
                Arrays.asList(environment.getActiveProfiles()),
                sources,
                page.items(),
                metadataCatalog.suggestions(),
                page.page(),
                (int) sorted.stream().filter(ConfigPropertyDto::override).count());
    }

    @PostMapping("/overrides")
    public ConfigOverrideResult put(@RequestBody ConfigOverrideRequest request) {
        if (request == null || request.name() == null) {
            throw new IllegalArgumentException("'name' must be provided");
        }
        return overrideService.put(request.name(), request.value());
    }

    @DeleteMapping("/overrides/{name}")
    public ConfigOverrideResult delete(@PathVariable String name) {
        return overrideService.remove(name);
    }

    private ConfigPropertyDto toDto(String name, Object value, String sourceName) {
        boolean isOverride = BootUiOverridesPropertySource.NAME.equals(sourceName);
        ConfigPropertySuggestionDto metadata = metadataCatalog.get(name);
        Object displayValue = value;
        boolean masked = false;
        ValueExposure valueExposure = exposure.valueExposure();
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            displayValue = null;
        } else if (valueExposure == ValueExposure.MASKED && exposure.maskSecrets() && masker.shouldMask(name, value)) {
            displayValue = SecretMasker.MASKED_VALUE;
            masked = true;
        }
        return new ConfigPropertyDto(
                name,
                displayValue,
                sourceName,
                null,
                masked,
                isOverride,
                metadata == null ? null : metadata.description(),
                metadata == null ? null : metadata.defaultValue());
    }

    private boolean matchesSource(ConfigPropertyDto property, String source) {
        return source.isEmpty() || source.equals(property.source());
    }

    private boolean matchesOverrideFilter(ConfigPropertyDto property, boolean overridesOnly) {
        return !overridesOnly || property.override();
    }

    private boolean matchesQuery(ConfigPropertyDto property, String query) {
        return PagedList.contains(property.name(), query)
                || PagedList.contains(text(property.value()), query)
                || PagedList.contains(property.description(), query)
                || PagedList.contains(text(property.defaultValue()), query);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
