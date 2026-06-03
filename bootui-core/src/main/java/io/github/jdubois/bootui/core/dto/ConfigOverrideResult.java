package io.github.jdubois.bootui.core.dto;

/**
 * Result of mutating a property override.
 */
public record ConfigOverrideResult(
        String name, String value, String previousValue, boolean persisted, String message) {}
