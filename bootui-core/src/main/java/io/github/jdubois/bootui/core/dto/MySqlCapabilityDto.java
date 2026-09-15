package io.github.jdubois.bootui.core.dto;

/**
 * Readability is READABLE, DENIED, UNKNOWN or UNAVAILABLE; collection and timing are ENABLED, DISABLED,
 * UNKNOWN or NOT_APPLICABLE. Readability alone never establishes complete collection.
 */
public record MySqlCapabilityDto(
        String id, String source, String scope, String readability, String collection, String timing, String reason) {}
