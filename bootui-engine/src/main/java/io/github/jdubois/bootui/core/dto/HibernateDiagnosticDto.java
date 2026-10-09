package io.github.jdubois.bootui.core.dto;

/**
 * One thing the Hibernate Advisor scan could not do, or could only do partially: a rule whose required evidence was
 * unavailable for a persistence unit, a rule evaluation that failed, or a discovery gap.
 *
 * <p>Diagnostics are reported next to the findings and never counted as violations. Messages are built from
 * controlled phrases only; they never carry query text, property values, or exception messages.</p>
 *
 * @param source a rule id such as {@code HIB-QUERY-006}, {@code discovery} for observation gaps, or
 *     {@code diagnostics} for the final entry that counts entries omitted by the size cap
 * @param unit the persistence-unit label, or {@code application} for application-wide evaluations
 * @param level {@code ERROR} (failed evaluation), {@code WARNING} (evidence unavailable), or {@code INFO} (evidence
 *     this advisor does not observe by design)
 * @param message the human-readable description of what was missing
 */
public record HibernateDiagnosticDto(String source, String unit, String level, String message) {}
