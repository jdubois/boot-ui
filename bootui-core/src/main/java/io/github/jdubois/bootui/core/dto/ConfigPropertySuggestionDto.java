package io.github.jdubois.bootui.core.dto;

/**
 * A known configuration property that can be used for new overrides.
 */
public record ConfigPropertySuggestionDto(String name, String type, String description, Object defaultValue) {}
