package io.github.jdubois.bootui.core.dto;

/**
 * Request to add/update a runtime property override.
 */
public record ConfigOverrideRequest(String name, String value, Boolean persist) {}
