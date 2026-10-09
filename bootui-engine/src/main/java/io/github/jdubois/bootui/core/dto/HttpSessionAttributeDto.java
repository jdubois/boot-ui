package io.github.jdubois.bootui.core.dto;

/**
 * Stable browser-facing representation of one HTTP session attribute.
 */
public record HttpSessionAttributeDto(String name, String type, String value, boolean masked, boolean truncated) {}
