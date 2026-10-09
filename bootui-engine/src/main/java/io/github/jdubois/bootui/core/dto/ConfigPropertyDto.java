package io.github.jdubois.bootui.core.dto;

/**
 * A single configuration property value, with its source.
 */
public record ConfigPropertyDto(
        String name,
        Object value,
        String source,
        String origin,
        boolean masked,
        boolean override,
        String description,
        Object defaultValue) {

    public ConfigPropertyDto {
        value = DtoCollections.immutableValue(value);
        defaultValue = DtoCollections.immutableValue(defaultValue);
    }
}
