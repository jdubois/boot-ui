package io.github.jdubois.bootui.core.dto;

/**
 * One thing the PostgreSQL read could not do, or could only do partially: a datasource that refused a
 * connection, a statistics view a role cannot see, an extension that is not installed, or a bound that
 * truncated the read.
 *
 * <p>Diagnostics are reported next to the findings and never counted as findings, so an incomplete read is
 * visible without being mistaken for a clean one. Messages are credential-redacted and truncated.</p>
 *
 * @param source what the diagnostic is about — a datasource name, or {@code datasource/section}
 * @param level {@code ERROR}, {@code WARNING} or {@code INFO}
 * @param message the human-readable description
 */
public record PostgresDiagnosticDto(String source, String level, String message) {}
