package io.github.jdubois.bootui.autoconfigure.memory;

import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;
import java.util.ArrayList;
import java.util.List;

final class MemoryRuleSupport {

    static final String PASS = "PASS";
    static final String VIOLATION = "VIOLATION";
    static final String SKIPPED = "SKIPPED";
    static final String ERROR = "ERROR";

    static final String CRITICAL = "CRITICAL";
    static final String HIGH = "HIGH";
    static final String MEDIUM = "MEDIUM";
    static final String LOW = "LOW";
    static final String INFO = "INFO";

    private static final java.util.Set<String> KNOWN_SEVERITIES = java.util.Set.of(CRITICAL, HIGH, MEDIUM, LOW, INFO);

    private static final int MAX_SAMPLE_VIOLATIONS = 10;
    private static final int MAX_DETAIL_CHARS = 240;

    private MemoryRuleSupport() {}

    static MemoryRuleResultDto pass(MemoryRuleDefinition definition) {
        return result(definition, PASS, 0, List.of());
    }

    static MemoryRuleResultDto skipped(MemoryRuleDefinition definition, String reason) {
        return result(definition, SKIPPED, 0, List.of(detail(reason)));
    }

    static MemoryRuleResultDto error(MemoryRuleDefinition definition, String reason) {
        return result(definition, ERROR, 0, List.of(detail(reason)));
    }

    static MemoryRuleResultDto violation(MemoryRuleDefinition definition, List<String> details) {
        return violation(definition, null, details);
    }

    /**
     * Builds a violation result, optionally overriding the rule's declared severity (for the few
     * rules whose risk depends on observed runtime conditions). A {@code null} or unknown override
     * falls back to the definition severity.
     */
    static MemoryRuleResultDto violation(
            MemoryRuleDefinition definition, String severityOverride, List<String> details) {
        return result(definition, VIOLATION, severityOverride, details.size(), samples(details));
    }

    static MemoryRuleResultDto result(
            MemoryRuleDefinition definition, String status, int violationCount, List<String> sampleViolations) {
        return result(definition, status, null, violationCount, sampleViolations);
    }

    static MemoryRuleResultDto result(
            MemoryRuleDefinition definition,
            String status,
            String severityOverride,
            int violationCount,
            List<String> sampleViolations) {
        String severity = severityOverride != null && KNOWN_SEVERITIES.contains(severityOverride)
                ? severityOverride
                : definition.severity();
        return new MemoryRuleResultDto(
                definition.id(),
                definition.name(),
                definition.category().label(),
                severity,
                definition.description(),
                status,
                violationCount,
                List.copyOf(sampleViolations),
                definition.recommendation(),
                definition.learnMoreUrl());
    }

    static String detail(String value) {
        if (value == null) {
            return "No additional detail.";
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (sanitized.isBlank()) {
            return "No additional detail.";
        }
        if (sanitized.length() <= MAX_DETAIL_CHARS) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_DETAIL_CHARS - 3) + "...";
    }

    private static List<String> samples(List<String> details) {
        List<String> samples = new ArrayList<>();
        for (String detail : details) {
            if (samples.size() >= MAX_SAMPLE_VIOLATIONS) {
                break;
            }
            samples.add(detail(detail));
        }
        return samples;
    }
}
