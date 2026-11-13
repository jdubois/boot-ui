package io.github.jdubois.bootui.core.dto;

/** Metadata-only validation or binding diagnostic for a single exception occurrence. */
public record BindingDiagnosticDto(String targetType, String fieldPath, String reason) {}
