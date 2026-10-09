package io.github.jdubois.bootui.core.dto;

/**
 * One auto-configuration evaluation entry.
 */
public record ConditionEntry(String autoConfigurationClass, String condition, String message, String outcome) {}
