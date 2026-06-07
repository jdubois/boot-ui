package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import java.util.ArrayList;
import java.util.List;

final class SpringRuleSupport {

    static final String PASS = "PASS";
    static final String VIOLATION = "VIOLATION";
    static final String SKIPPED = "SKIPPED";
    static final String ERROR = "ERROR";

    private static final int MAX_SAMPLE_VIOLATIONS = 10;
    private static final int MAX_DETAIL_CHARS = 240;

    private SpringRuleSupport() {}

    static SpringRuleResultDto pass(SpringRuleDefinition definition) {
        return result(definition, PASS, 0, List.of());
    }

    static SpringRuleResultDto skipped(SpringRuleDefinition definition, String reason) {
        return result(definition, SKIPPED, 0, List.of(detail(reason)));
    }

    static SpringRuleResultDto error(SpringRuleDefinition definition, String reason) {
        return result(definition, ERROR, 0, List.of(detail(reason)));
    }

    static SpringRuleResultDto violation(SpringRuleDefinition definition, List<String> details) {
        return result(definition, VIOLATION, details.size(), samples(details));
    }

    static SpringRuleResultDto result(
            SpringRuleDefinition definition, String status, int violationCount, List<String> sampleViolations) {
        return new SpringRuleResultDto(
                definition.id(),
                definition.name(),
                definition.category().label(),
                definition.severity(),
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
