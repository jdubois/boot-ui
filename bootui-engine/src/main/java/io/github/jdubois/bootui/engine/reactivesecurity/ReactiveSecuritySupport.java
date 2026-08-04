package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared pass/skip/violation/error result-building helpers for {@code SEC-RXF-*} rules.
 */
final class ReactiveSecuritySupport {

    static final String PASS = "PASS";
    static final String VIOLATION = "VIOLATION";
    static final String SKIPPED = "SKIPPED";
    static final String ERROR = "ERROR";

    static final String CRITICAL = "CRITICAL";
    static final String HIGH = "HIGH";
    static final String MEDIUM = "MEDIUM";
    static final String LOW = "LOW";
    static final String INFO = "INFO";

    private static final Set<String> KNOWN_SEVERITIES = Set.of(CRITICAL, HIGH, MEDIUM, LOW, INFO);

    private static final int MAX_SAMPLE_VIOLATIONS = 10;
    private static final int MAX_DETAIL_CHARS = 240;

    private ReactiveSecuritySupport() {}

    static SecurityRuleResultDto pass(ReactiveSecurityRuleDefinition definition) {
        return result(definition, PASS, 0, List.of());
    }

    static SecurityRuleResultDto skipped(ReactiveSecurityRuleDefinition definition, String reason) {
        return result(definition, SKIPPED, 0, List.of(detail(reason)));
    }

    static SecurityRuleResultDto error(ReactiveSecurityRuleDefinition definition, String reason) {
        return result(definition, ERROR, 0, List.of(detail(reason)));
    }

    static SecurityRuleResultDto violation(ReactiveSecurityRuleDefinition definition, List<String> details) {
        return violation(definition, null, details);
    }

    /**
     * Builds a violation result, optionally overriding the rule's declared severity (for the few
     * rules whose risk depends on the configuration context, e.g. credentialed vs. non-credentialed
     * CORS). A {@code null} or unknown override falls back to the definition severity.
     */
    static SecurityRuleResultDto violation(
            ReactiveSecurityRuleDefinition definition, String severityOverride, List<String> details) {
        return result(definition, VIOLATION, severityOverride, details.size(), samples(details));
    }

    static SecurityRuleResultDto result(
            ReactiveSecurityRuleDefinition definition,
            String status,
            int violationCount,
            List<String> sampleViolations) {
        return result(definition, status, null, violationCount, sampleViolations);
    }

    static SecurityRuleResultDto result(
            ReactiveSecurityRuleDefinition definition,
            String status,
            String severityOverride,
            int violationCount,
            List<String> sampleViolations) {
        String severity = severityOverride != null && KNOWN_SEVERITIES.contains(severityOverride)
                ? severityOverride
                : definition.severity();
        return new SecurityRuleResultDto(
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
