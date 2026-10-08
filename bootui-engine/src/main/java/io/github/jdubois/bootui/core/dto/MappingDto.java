package io.github.jdubois.bootui.core.dto;

/**
 * One HTTP mapping.
 */
public record MappingDto(String method, String pattern, String handler, String produces, String consumes) {}
