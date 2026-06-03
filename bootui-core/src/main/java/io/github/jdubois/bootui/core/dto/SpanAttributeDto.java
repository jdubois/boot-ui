package io.github.jdubois.bootui.core.dto;

/**
 * Summary describing a span attribute, normalized to a JSON-friendly value type.
 */
public record SpanAttributeDto(String key, String type, Object value) {}
