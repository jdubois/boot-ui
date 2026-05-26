package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.autoconfigure.config.BootUiOverridesPropertySource;
import io.github.jdubois.bootui.autoconfigure.config.ConfigOverrideService;
import io.github.jdubois.bootui.core.BootUiDtos.ConfigOverrideRequest;
import io.github.jdubois.bootui.core.BootUiDtos.ConfigOverrideResult;
import io.github.jdubois.bootui.core.BootUiDtos.ConfigPropertyDto;
import io.github.jdubois.bootui.core.BootUiDtos.ConfigPropertySuggestionDto;
import io.github.jdubois.bootui.core.BootUiDtos.ConfigReport;
import io.github.jdubois.bootui.core.SecretMasker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/config")
public class ConfigController {

    private final ConfigurableEnvironment environment;

    private final ConfigOverrideService overrideService;

    private final BootUiProperties properties;

    private final ConfigMetadataCatalog metadataCatalog;

    private final SecretMasker masker = new SecretMasker();

    @Autowired
    public ConfigController(ConfigurableEnvironment environment,
                            ConfigOverrideService overrideService,
                            BootUiProperties properties) {
        this(environment, overrideService, properties, new ConfigMetadataCatalog(ConfigController.class.getClassLoader()));
    }

    ConfigController(ConfigurableEnvironment environment,
                     ConfigOverrideService overrideService,
                     BootUiProperties properties,
                     ConfigMetadataCatalog metadataCatalog) {
        this.environment = environment;
        this.overrideService = overrideService;
        this.properties = properties;
        this.metadataCatalog = metadataCatalog;
    }

    @GetMapping
    public ConfigReport list() {
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
        return new ConfigReport(
                Arrays.asList(environment.getActiveProfiles()),
                sources,
                sorted,
                metadataCatalog.suggestions());
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
        if (properties.getExposeValues() == ValueExposure.METADATA_ONLY) {
            displayValue = null;
        } else if (properties.getExposeValues() == ValueExposure.MASKED
                && properties.isMaskSecrets()
                && masker.isSecret(name)) {
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
}
