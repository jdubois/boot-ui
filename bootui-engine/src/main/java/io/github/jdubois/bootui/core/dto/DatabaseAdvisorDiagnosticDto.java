package io.github.jdubois.bootui.core.dto;

/**
 * One thing the Database Advisor scan could not do, or could only do partially: a datasource that refused a
 * connection, a table whose metadata could not be read, a catalog augmentation blocked by permissions, a
 * bound that truncated the scan, or a rule that was skipped or errored.
 *
 * <p>Diagnostics are reported next to the findings and never counted as violations, so an incomplete scan is
 * visible without inflating the advisor score. Messages are credential-redacted and truncated.</p>
 *
 * @param source what the diagnostic is about — a datasource name, {@code datasource/table}, or a rule id
 * @param level {@code ERROR}, {@code WARNING} or {@code INFO}
 * @param message the human-readable description
 */
public record DatabaseAdvisorDiagnosticDto(String source, String level, String message) {}
