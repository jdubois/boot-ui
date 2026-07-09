package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.engine.support.DetailText;
import java.util.ArrayList;
import java.util.List;

final class HibernateRuleSupport {

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

    private HibernateRuleSupport() {}

    static HibernateRuleResultDto pass(HibernateRuleDefinition definition) {
        return result(definition, PASS, 0, List.of());
    }

    static HibernateRuleResultDto skipped(HibernateRuleDefinition definition, String reason) {
        return result(definition, SKIPPED, 0, List.of(detail(reason)));
    }

    static HibernateRuleResultDto error(HibernateRuleDefinition definition, String reason) {
        return result(definition, ERROR, 0, List.of(detail(reason)));
    }

    static HibernateRuleResultDto violation(HibernateRuleDefinition definition, List<String> details) {
        return violation(definition, null, details);
    }

    /**
     * Builds a violation result, optionally overriding the rule's declared severity (for the few
     * rules whose risk depends on the active profile, declared value, or mapping context). A
     * {@code null} or unknown override falls back to the definition severity.
     */
    static HibernateRuleResultDto violation(
            HibernateRuleDefinition definition, String severityOverride, List<String> details) {
        return result(definition, VIOLATION, severityOverride, details.size(), samples(details));
    }

    static HibernateRuleResultDto result(
            HibernateRuleDefinition definition, String status, int violationCount, List<String> sampleViolations) {
        return result(definition, status, null, violationCount, sampleViolations);
    }

    static HibernateRuleResultDto result(
            HibernateRuleDefinition definition,
            String status,
            String severityOverride,
            int violationCount,
            List<String> sampleViolations) {
        String severity = severityOverride != null && KNOWN_SEVERITIES.contains(severityOverride)
                ? severityOverride
                : definition.severity();
        return new HibernateRuleResultDto(
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
        return DetailText.sanitize(value);
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
