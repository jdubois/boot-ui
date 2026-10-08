package io.github.jdubois.bootui.core.dto;

/** Exact nonnegative decimal value, or null for unknown; unit and evidence provenance are explicit. */
public record MySqlMetricDto(String id, String label, String value, String unit, String scope, String source) {}
