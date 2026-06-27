package io.github.jdubois.bootui.engine.telemetry;

import java.util.Map;

/**
 * Normalized span event with a time offset relative to its parent span and
 * attribute map coerced to JSON-friendly types.
 */
public record NormalizedEvent(String name, long timeOffsetNanos, Map<String, AttributeValue> attributes) {}
