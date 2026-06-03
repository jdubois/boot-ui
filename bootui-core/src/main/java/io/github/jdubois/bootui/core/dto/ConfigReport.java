package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ConfigReport(
        List<String> activeProfiles,
        List<String> sources,
        List<ConfigPropertyDto> properties,
        List<ConfigPropertySuggestionDto> propertySuggestions,
        PageMetadata page,
        int overrideCount) {
    public ConfigReport(
            List<String> activeProfiles,
            List<String> sources,
            List<ConfigPropertyDto> properties,
            List<ConfigPropertySuggestionDto> propertySuggestions) {
        this(
                activeProfiles,
                sources,
                properties,
                propertySuggestions,
                new PageMetadata(properties.size(), properties.size(), 0, properties.size(), properties.size(), false),
                (int) properties.stream().filter(ConfigPropertyDto::override).count());
    }
}
