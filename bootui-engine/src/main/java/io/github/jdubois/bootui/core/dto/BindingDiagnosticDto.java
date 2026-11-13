package io.github.jdubois.bootui.core.dto;

/**
 * Metadata-only validation or binding diagnostic for a single exception occurrence.
 *
 * <p>Captures the target DTO/bean or handler type, the normalized property or parameter path, and
 * the constraint annotation name, binding error code, or expected target type. Rejected user input
 * values and raw parser tokens are never retained.</p>
 */
public record BindingDiagnosticDto(String targetType, String fieldPath, String reason) {}
